plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
}

tasks.register("syncFixtures") {
    group = "verification"
    description = "Copies Python golden fixtures and the optional 2026-08-03 replay into JVM test resources."
    dependsOn(":core:pipeline:syncFixtures")
}
