plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); alias(libs.plugins.compose); alias(libs.plugins.compose.compiler) }
kotlin { jvmToolchain(21) }
val embeddedServer = layout.buildDirectory.dir("generated/serverResource")
val embedServerJar by tasks.registering(Sync::class) { dependsOn(":server-android:serverJar", ":windows-shell-bridge:buildNative"); from(project(":server-android").layout.buildDirectory.file("server/droidfiles-server.jar")) { into("server") }; from(project(":windows-shell-bridge").layout.buildDirectory.file("native/droidfiles_shell_bridge.dll")) { into("native") }; into(embeddedServer) }
sourceSets.main { resources.srcDir(embeddedServer) }
tasks.processResources { dependsOn(embedServerJar) }
dependencies {
    implementation(project(":client-core")); implementation(project(":transport-adb")); implementation(project(":privilege")); implementation(project(":platform-windows")); implementation(compose.desktop.currentOs); implementation(compose.material3); implementation(libs.coroutines.core); implementation(libs.serialization.json); testImplementation(compose.desktop.uiTestJUnit4); testImplementation("junit:junit:4.13.2"); testImplementation(
    platform(libs.junit.bom)
); testImplementation(libs.junit.jupiter); testRuntimeOnly("org.junit.vintage:junit-vintage-engine"); testRuntimeOnly(libs.junit.launcher)
}
tasks.test { useJUnitPlatform() }
compose.desktop {
    application {
        mainClass = "dev.droidfiles.desktop.MainKt"; javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().metadata.installationPath.asFile.absolutePath; nativeDistributions {
        modules("jdk.unsupported"); targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi); packageName = providers.gradleProperty("app.name").get(); packageVersion = project.version.toString()
    }
    }
}
