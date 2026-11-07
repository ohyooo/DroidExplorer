plugins { alias(libs.plugins.android.application) }

android {
    namespace = "dev.droidfiles.server"
    compileSdk {
        version = release(libs.versions.compile.sdk.get().toInt()) {
            minorApiLevel = libs.versions.compile.minor.get().toInt()
        }
    }
    defaultConfig {
        applicationId = "dev.droidfiles.server"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = project.version.toString()
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = null } }
}
dependencies { implementation(project(":protocol")); implementation(libs.coroutines.core) }

val serverJar by tasks.registering(Copy::class) { dependsOn("assembleRelease"); from(layout.buildDirectory.file("outputs/apk/release/server-android-release-unsigned.apk")); into(layout.buildDirectory.dir("server")); rename { "droidfiles-server.jar" } }
tasks.named("build") { dependsOn(serverJar) }

