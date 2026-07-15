package dev.droidfiles.windows

import java.awt.Desktop
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.filechooser.FileSystemView

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
    val suffix = if (directory) "" else fileName.substringAfterLast('.', "").take(32).filter { it.isLetterOrDigit() }
        .takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
    val probe = if (directory) Files.createTempDirectory("droidfiles-folder-icon-") else Files.createTempFile("droidfiles-file-icon-", suffix)
    return try {
        val icon = FileSystemView.getFileSystemView().getSystemIcon(probe.toFile(), size, size)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        ByteArrayOutputStream().use { output -> ImageIO.write(image, "png", output); output.toByteArray() }
    } finally {
        runCatching { Files.deleteIfExists(probe) }
    }
}
