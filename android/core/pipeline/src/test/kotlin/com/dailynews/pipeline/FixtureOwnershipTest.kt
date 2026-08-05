package com.dailynews.pipeline

import java.security.MessageDigest
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** KEEP: proves the frozen Kotlin-owned synthetic fixture set is present byte-for-byte. */
class FixtureOwnershipTest {
    @Test
    fun `vendored fixtures match the Phase A freeze manifest`() {
        expectedHashes.forEach { (name, expected) ->
            val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) {
                "missing vendored fixture: $name"
            }.use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual, name)
        }
    }

    private companion object {
        val expectedHashes = mapOf(
            "all_empty_but_ok.json" to "20ce79835e71fa69d4445d949542c6526d1787058d2a85e9fa3c01fdda2fa635",
            "all_error.json" to "286a16bf801cbf7ecd434781a48bd94e1fc84bd60c038c14e3af8c13d3457e3e",
            "article_samples.json" to "55ebbe24e4f00d21f6c45bbffda7648bbaf22a7bed24f1a1ccc22a8e0c700a87",
            "dedup_collision.json" to "dfdf7b9e172e070b4c636ce6d173b5a73f474d3b77c7eff2ddd1af5d1a017018",
            "feeds_fixture.json" to "f919f787d82791adcae9bb3cb711be173f5a63deca69e0625a13c85d85eb1efe",
            "golden_success.json" to "a3ac67d060351ec2d4bda6e23979d10c8a4e71259fee59e36757dc22d48b23b5",
            "llm_context_golden.json" to "6dba5835fa6d9208e1f36e70291bbf579905eca721c7fc94b879bd36bab328da",
            "markdown_render_golden.md" to "9e7b4f41850558554e3a9b18e6e60a92ebe325748229bc4afb21974cc679f25f",
            "partial_failure_mix.json" to "0b9341e42e2ff4b3dbbfa4b16fb24bcd92e871ba49ea93ce50060f3d45af6ebc",
            "pipeline_config_fixture.json" to "1041a605eb79d4d426ccbc82653236c0a9b046b568ac3c64c2d20cdc2c965d5b",
        )
    }
}
