plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.protobuf) apply false
}

allprojects {
    group = providers.gradleProperty("app.group").get()
    version = providers.gradleProperty("app.version").get()
}

tasks.register("packageRelease") { group = "distribution"; dependsOn(":windows-shell-bridge:buildNative", ":desktop-app:createDistributable") }
tasks.register("integrationTest") { group = "verification"; dependsOn("build"); doLast { println("Run integration-tests/android-e2e.ps1 with an attached emulator for device verification") } }
