plugins { alias(libs.plugins.kotlin.jvm); application }
kotlin { jvmToolchain(21) }
dependencies { implementation(project(":client-core")); implementation(project(":transport-adb")); implementation(project(":privilege")); implementation(libs.coroutines.core) }
application { mainClass = "dev.droidfiles.cli.MainKt" }
tasks.register<JavaExec>("benchmarkTransfer") { group = "verification"; classpath = sourceSets.main.get().runtimeClasspath; mainClass.set("dev.droidfiles.cli.BenchmarkEntry"); args(listOf("benchSerial", "benchJar", "benchSource", "benchRemote", "benchTarget").mapNotNull { providers.gradleProperty(it).orNull }) }
