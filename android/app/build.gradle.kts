import java.io.File
import java.util.Properties
import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Release signing is opt-in and local-only. Credentials come from the gitignored
// `keystore.properties`, or from env vars for a build that must not write the
// password to disk. With neither configured the release variant stays unsigned
// so `assembleRelease` still works on a fresh clone — but a *partially* filled
// keystore.properties is a hard error, because silently handing back an
// unsigned APK is exactly the failure this repo refuses everywhere else.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use(::load)
}

// A blank value in the file counts as absent, so it falls through to the env
// var rather than pinning the input to "".
fun signingInput(key: String, env: String): String? =
    keystoreProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }

val storeFileInput = signingInput("storeFile", "DAILYNEWS_STORE_FILE")
val storePasswordInput = signingInput("storePassword", "DAILYNEWS_STORE_PASSWORD")
val keyAliasInput = signingInput("keyAlias", "DAILYNEWS_KEY_ALIAS")
// keytool's "press RETURN if same as keystore password" is the recommended path
// for a self-signed app key, so an absent keyPassword means "same as store".
val keyPasswordInput = signingInput("keyPassword", "DAILYNEWS_KEY_PASSWORD") ?: storePasswordInput

val releaseKeystore: File? = storeFileInput?.let { raw ->
    val expanded = if (raw.startsWith("~/")) System.getProperty("user.home") + raw.drop(1) else raw
    File(expanded).takeIf { it.isAbsolute } ?: rootProject.file(expanded)
}

if (keystorePropertiesFile.exists()) {
    val missing = listOf(
        "storeFile" to storeFileInput,
        "storePassword" to storePasswordInput,
        "keyAlias" to keyAliasInput,
    ).filter { it.second == null }.map { it.first }
    check(missing.isEmpty()) {
        "keystore.properties exists but is missing ${missing.joinToString()}. " +
            "Fill it in or delete the file — a half-filled config would quietly produce an unsigned APK."
    }
    check(releaseKeystore?.isFile == true) {
        "keystore.properties points at a keystore that is not there: $storeFileInput"
    }
}

val releaseSigningReady = releaseKeystore?.isFile == true &&
    storePasswordInput != null && keyAliasInput != null && keyPasswordInput != null

android {
    namespace = "com.dailynews.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dailynews.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = storePasswordInput
                keyAlias = keyAliasInput
                keyPassword = keyPasswordInput
                // minSdk 26 is past the v1 (JAR signing) era; AGP already drops
                // v1 here, and v2/v3 stay on by default.
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Null when nothing is configured: the variant then packages as
            // app-release-unsigned.apk instead of failing the build.
            signingConfig = signingConfigs.findByName("release")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val generatedSeedAssets = layout.buildDirectory.dir("generated/seedAssets")
val syncSeedFeeds by tasks.registering(Copy::class) {
    from(rootProject.file("../feeds.json"))
    into(generatedSeedAssets.map { it.dir("seed") })
}
android.sourceSets["main"].assets.srcDir(generatedSeedAssets)
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(syncSeedFeeds)
    if (name.contains("lint", ignoreCase = true)) dependsOn(syncSeedFeeds)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:model"))
    implementation(project(":core:llm"))
    implementation(project(":core:pipeline"))
    implementation(project(":core:data"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.window.size)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.adaptive)
    implementation(libs.adaptive.layout)
    implementation(libs.adaptive.navigation)
    implementation(libs.adaptive.navigation.suite)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime.ktx)
    implementation(libs.browser)
    implementation(libs.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.window)
    compileOnly(libs.error.prone.annotations)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.glance.appwidget.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
