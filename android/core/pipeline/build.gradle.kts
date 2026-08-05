import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:llm"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.jsoup)
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(kotlin("test"))
}

val generatedMigrationGuardDir = layout.buildDirectory.dir("generated-migration-guard-resources")

// V2 Phase A: synthetic KEEP fixtures are vendored under src/test/resources.
// This task intentionally copies local-only real-run evidence for
// MIGRATION-GUARD tests only; Phase C retires it one guard at a time.
val syncMigrationGuards by tasks.registering(Copy::class) {
    val replayDir = rootProject.file("../runs/2026-08-03")
    if (replayDir.exists()) {
        from(replayDir) {
            include("raw.json", "llm_context.json", "validation.json", "part1_brief.json", "part2_context.json", "context_budget.json", "part1_plan.json", "part2_draft.json", "top30.md")
            into("replay/2026-08-03")
        }
    }
    val report = rootProject.file("../rss-report-2026-08-03.md")
    if (report.exists()) {
        from(report) { into("replay/2026-08-03") }
    }
    into(generatedMigrationGuardDir)
}

sourceSets.test {
    resources.srcDir(generatedMigrationGuardDir)
}

tasks.test {
    dependsOn(syncMigrationGuards)
    useJUnitPlatform()
}

tasks.named("processTestResources") {
    dependsOn(syncMigrationGuards)
}
