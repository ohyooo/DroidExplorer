plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); alias(libs.plugins.protobuf) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.coroutines.core); api(libs.serialization.json); api(libs.protobuf.javalite)
    testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter); testRuntimeOnly(libs.junit.launcher)
}
tasks.test { useJUnitPlatform() }
val protobufVersion = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs").findVersion("protobuf").get().requiredVersion
protobuf { protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }; generateProtoTasks { all().configureEach { builtins { named("java") { option("lite") } } } } }
