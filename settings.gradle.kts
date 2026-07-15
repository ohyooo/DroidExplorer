pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "DroidFiles"
include(":protocol", ":client-core", ":transport-adb", ":server-android", ":cli", ":testkit", ":desktop-app", ":platform-windows")
include(":windows-shell-bridge")
include(":privilege")
