package com.dailynews.data.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dailynews.llm.ApiKeySource
import javax.crypto.AEADBadTagException

/**
 * Encrypted storage for provider keys.
 *
 * Opening is lazy and never fatal: a restored-from-backup device can hold a
 * key file its Keystore can no longer decrypt, and construction happens on the
 * Application main thread. A vault that cannot be opened reads as empty rather
 * than taking the process down, so the user can simply re-enter their keys.
 */
class ApiKeyVault(context: Context) : ApiKeySource {
    private val appContext = context.applicationContext
    @Volatile private var preferences: SharedPreferences? = null

    override fun read(alias: String): String? {
        val store = preferences() ?: return null
        return runCatching { store.getString(alias, null) }.getOrElse {
            invalidate()
            if (it.isPermanentlyUnrecoverableCiphertext()) recoverPermanent()
            null
        }
    }

    fun write(alias: String, key: String) {
        val store = preferences() ?: error("secure key storage is unavailable on this device")
        runCatching { store.edit().putString(alias, key).commit() }
            .getOrElse {
                invalidate()
                val reopened = (if (it.isPermanentlyUnrecoverableCiphertext()) recoverPermanent() else null)
                    ?: error("secure key storage is temporarily unavailable; provider keys were preserved")
                check(reopened.edit().putString(alias, key).commit()) { "unable to store provider key" }
            }
    }

    fun delete(alias: String) {
        val store = preferences() ?: return
        runCatching { store.edit().remove(alias).commit() }.onFailure { invalidate() }
    }

    private fun preferences(): SharedPreferences? {
        preferences?.let { return it }
        return synchronized(this) {
            preferences ?: runCatching(::open).getOrElse { error ->
                if (error.isPermanentlyUnrecoverableCiphertext()) runCatching(::recreatePreferences).getOrNull() else null
            }?.also { preferences = it }
        }
    }

    private fun invalidate() = synchronized(this) {
        preferences = null
    }

    /** Deletes only ciphertext proven unreadable; never deletes the shared AndroidX master-key alias. */
    private fun recoverPermanent(): SharedPreferences? = synchronized(this) {
        preferences = null
        runCatching(::recreatePreferences).getOrNull()?.also { preferences = it }
    }

    private fun recreatePreferences(): SharedPreferences {
        appContext.deleteSharedPreferences(PREFERENCES_NAME)
        return open()
    }

    private fun open(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "provider_keys"
    }
}

private fun Throwable.isPermanentlyUnrecoverableCiphertext(): Boolean {
    val chain = generateSequence(this) { it.cause }.toList()
    if (chain.any { it is AEADBadTagException }) return true
    // AndroidX/Tink wraps a restored keyset mismatch in GeneralSecurityException. Keystore
    // availability and KeyStoreException are intentionally not classified as permanent.
    return chain.any { error ->
        val text = error.message.orEmpty().lowercase()
        ("decrypt" in text || "invalid mac" in text || "tag mismatch" in text) &&
            ("keyset" in text || "ciphertext" in text || "encrypted" in text)
    }
}
