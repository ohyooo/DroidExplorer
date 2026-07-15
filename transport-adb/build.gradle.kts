plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies { api(project(":client-core")); implementation(libs.coroutines.core); testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter); testRuntimeOnly(libs.junit.launcher) }
tasks.test { useJUnitPlatform() }
