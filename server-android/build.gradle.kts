plugins { alias(libs.plugins.android.application) }

android {
    namespace = "dev.droidfiles.server"; compileSdk = 37
    defaultConfig { applicationId = "dev.droidfiles.server"; minSdk = 23; targetSdk = 37; versionCode = 1; versionName = project.version.toString() }
    buildTypes { release { isMinifyEnabled = false; signingConfig = null } }
}
dependencies { implementation(project(":protocol")); implementation(libs.coroutines.core) }

val serverJar by tasks.registering(Copy::class) { dependsOn("assembleRelease"); from(layout.buildDirectory.file("outputs/apk/release/server-android-release-unsigned.apk")); into(layout.buildDirectory.dir("server")); rename { "droidfiles-server.jar" } }
tasks.named("build") { dependsOn(serverJar) }

