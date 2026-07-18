plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
val nativeTestResources = layout.buildDirectory.dir("generated/nativeTestResources")
val embedNativeForTests = tasks.register<Sync>("embedNativeForTests") {
    dependsOn(":windows-shell-bridge:buildNative")
    from(project(":windows-shell-bridge").layout.buildDirectory.file("native/droidfiles_shell_bridge.dll")) { into("native") }
    into(nativeTestResources)
}
sourceSets.test { resources.srcDir(nativeTestResources) }
tasks.processTestResources { dependsOn(embedNativeForTests) }
dependencies { api(project(":client-core")); implementation(libs.coroutines.core); testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter); testRuntimeOnly(libs.junit.launcher) }
tasks.test { useJUnitPlatform() }
