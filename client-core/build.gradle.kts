plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(21) }
dependencies { api(project(":protocol")); api(libs.coroutines.core); testImplementation(project(":testkit")); testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter); testRuntimeOnly(libs.junit.launcher) }
tasks.test { useJUnitPlatform() }
