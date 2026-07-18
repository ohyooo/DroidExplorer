plugins { base }

val nativeBuildDir = layout.buildDirectory.dir("native")
private fun localToolOrPath(localPath: String, command: String): String =
    if (file(localPath).isFile) localPath else command

private fun quoteForCmd(value: String): String = "\"${value.replace("\"", "\\\"")}\""

val cmakeExecutable = providers.gradleProperty("cmake.path")
    .orElse(providers.environmentVariable("CMAKE_EXE"))
    .orElse(localToolOrPath("D:\\Documents\\Android\\cmake\\4.1.2\\bin\\cmake.exe", "cmake"))
val ninjaExecutable = providers.gradleProperty("ninja.path")
    .orElse(providers.environmentVariable("NINJA_EXE"))
    .orElse(localToolOrPath("D:\\Documents\\Android\\cmake\\4.1.2\\bin\\ninja.exe", "ninja"))
val vswhereExecutable = providers.gradleProperty("vswhere.path")
    .orElse(providers.environmentVariable("VSWHERE_EXE"))
    .orElse("C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe")

fun visualStudioEnvironmentCommand(): String {
    val vswhere = file(vswhereExecutable.get())
    require(vswhere.isFile) { "vswhere not found: $vswhere" }
    val installation = providers.exec {
        commandLine(vswhere, "-latest", "-products", "*", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath")
    }.standardOutput.asText.get().trim()
    require(installation.isNotEmpty()) { "Visual Studio C++ toolchain was not found by vswhere" }
    val vcvars = file("$installation/VC/Auxiliary/Build/vcvars64.bat")
    require(vcvars.isFile) { "vcvars64.bat not found: $vcvars" }
    return "call \"${vcvars.absolutePath}\""
}

val configureNative by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/main/cpp")); outputs.file(nativeBuildDir.map { it.file("build.ninja") })
    val javaHome = System.getProperty("java.home").replace('\\', '/')
    commandLine("cmd", "/d", "/s", "/c", "${visualStudioEnvironmentCommand()} && ${quoteForCmd(cmakeExecutable.get())} -S ${quoteForCmd(file("src/main/cpp").absolutePath)} -B ${quoteForCmd(nativeBuildDir.get().asFile.absolutePath)} -G Ninja -DCMAKE_MAKE_PROGRAM=${quoteForCmd(ninjaExecutable.get())} -DJAVA_HOME=${quoteForCmd(javaHome)}")
}
val buildNative by tasks.registering(Exec::class) { dependsOn(configureNative); commandLine("cmd", "/d", "/s", "/c", "${visualStudioEnvironmentCommand()} && ${quoteForCmd(cmakeExecutable.get())} --build ${quoteForCmd(nativeBuildDir.get().asFile.absolutePath)}") }
val testNative by tasks.registering(Exec::class) { dependsOn(buildNative); commandLine(nativeBuildDir.map { it.file("descriptor_tests.exe") }.get().asFile.absolutePath) }
tasks.named("check") { dependsOn(testNative) }; tasks.named("assemble") { dependsOn(buildNative) }
