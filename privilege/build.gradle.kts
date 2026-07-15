plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(21) }
dependencies { api(project(":transport-adb")); implementation(libs.coroutines.core); implementation(libs.serialization.json); testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter); testRuntimeOnly(libs.junit.launcher) }
tasks.test { useJUnitPlatform() }
