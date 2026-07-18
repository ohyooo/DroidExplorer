package dev.droidfiles.windows

import java.nio.file.Path
import java.util.Locale

interface PlatformShell {
    fun open(path: Path);
    fun reveal(path: Path);
    fun openWith(executable: Path, arguments: List<String>, file: Path, workingDirectory: Path? = null, wait: Boolean = false): Int?
}

class WindowsPlatformShell : PlatformShell {
    override fun open(path: Path) {
        if (!NativeDragBridge.available || NativeDragBridge.shellOpen(path.toAbsolutePath().toString()) <= 32) throw IllegalStateException("Windows could not open ${path.fileName}")
    };
    override fun reveal(path: Path) {
        ProcessBuilder("explorer.exe", "/select,${path.toAbsolutePath()}").start()
    };
    override fun openWith(executable: Path, arguments: List<String>, file: Path, workingDirectory: Path?, wait: Boolean): Int? {
        val command = listOf(executable.toString()) + arguments.map { it.replace("{file}", file.toAbsolutePath().toString()) };
        val process = ProcessBuilder(command).apply { workingDirectory?.let { directory(it.toFile()) } }.start(); return if (wait) process.waitFor() else null
    }
}

object NativeDragBridge {
    val available: Boolean by lazy {
        runCatching {
            val input = checkNotNull(javaClass.getResourceAsStream("/native/droidfiles_shell_bridge.dll"));
            val file = java.nio.file.Files.createTempFile("droidfiles-shell-bridge", ".dll"); input.use { java.nio.file.Files.copy(it, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }; file.toFile().deleteOnExit(); System.load(file.toAbsolutePath().toString()); true
        }.getOrDefault(false)
    };

    external fun beginVirtualFileDrag(paths: Array<String>, itemIds: Array<String>, sizes: LongArray, directories: BooleanArray, modifiedMillis: LongArray, port: Int, token: String): Int;
    external fun shellOpen(path: String): Int
    external fun shellIconPng(fileName: String, directory: Boolean, size: Int): ByteArray?
    external fun setClipboardFiles(paths: Array<String>): Int
    external fun setClipboardText(text: String): Int
}

class WindowsClipboard internal constructor(
    private val writer: (Array<String>) -> Int,
    private val textWriter: (String) -> Int = { 0 },
) {
    constructor() : this(nativeFileWriter, nativeTextWriter)

    fun copyFiles(paths: List<Path>) {
        require(paths.isNotEmpty()) { "At least one file is required" }
        val normalized = paths.map { it.toAbsolutePath().normalize().toString() }.toTypedArray()
        val result = writer(normalized)
        check(result == 0) { "Windows clipboard rejected the file list (error $result)" }
    }

    fun copyText(text: String) {
        val result = textWriter(text)
        check(result == 0) { "Windows clipboard rejected text (error $result)" }
    }

    private companion object {
        val nativeFileWriter: (Array<String>) -> Int = { paths ->
            check(NativeDragBridge.available) { "Windows Shell bridge is unavailable" }
            NativeDragBridge.setClipboardFiles(paths)
        }
        val nativeTextWriter: (String) -> Int = { text ->
            check(NativeDragBridge.available) { "Windows Shell bridge is unavailable" }
            NativeDragBridge.setClipboardText(text)
        }
    }
}

interface FileIconProvider {
    fun loadPng(fileName: String, directory: Boolean, size: Int = 20): ByteArray?
}

internal fun fileIconCacheKey(fileName: String, directory: Boolean, size: Int): String {
    if (directory) return "directory:$size"
    val dot = fileName.lastIndexOf('.')
    val extension = if (dot > 0 && dot < fileName.lastIndex) fileName.substring(dot).lowercase(Locale.ROOT) else ""
    return "file:$extension:$size"
}

class WindowsFileIconProvider internal constructor(
    private val loader: (String, Boolean, Int) -> ByteArray?,
) : FileIconProvider {
    constructor() : this(::loadWindowsShellIcon)

    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 512
    }

    override fun loadPng(fileName: String, directory: Boolean, size: Int): ByteArray? {
        val safeSize = size.coerceIn(16, 256)
        val key = fileIconCacheKey(fileName, directory, safeSize)
        synchronized(cache) { cache[key]?.let { return it.takeIf(ByteArray::isNotEmpty) } }
        val loaded = runCatching { loader(fileName, directory, safeSize) }.getOrNull()
        synchronized(cache) { cache[key] = loaded ?: ByteArray(0) }
        return loaded
    }
}

private fun loadWindowsShellIcon(fileName: String, directory: Boolean, size: Int): ByteArray? {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null
    if (!NativeDragBridge.available) return null
    return NativeDragBridge.shellIconPng(fileName, directory, size)
}
