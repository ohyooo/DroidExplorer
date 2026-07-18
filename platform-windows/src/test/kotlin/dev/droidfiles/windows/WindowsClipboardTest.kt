package dev.droidfiles.windows

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WindowsClipboardTest {
    @Test
    fun `file paths are normalized before entering the platform boundary`() {
        var received = emptyArray<String>()
        val clipboard = WindowsClipboard(writer = { paths -> received = paths; 0 })

        clipboard.copyFiles(listOf(Path.of("cache", "..", "缓存", "one file.txt")))

        assertArrayEquals(arrayOf(Path.of("缓存", "one file.txt").toAbsolutePath().normalize().toString()), received)
    }

    @Test
    fun `platform clipboard failures are reported`() {
        val clipboard = WindowsClipboard(writer = { 5 })
        assertThrows(IllegalStateException::class.java) { clipboard.copyFiles(listOf(Path.of("file.txt"))) }
    }

    @Test
    fun `unicode text is passed unchanged to the platform boundary`() {
        var received = ""
        val clipboard = WindowsClipboard({ 0 }, { text -> received = text; 0 })
        clipboard.copyText("/sdcard/相册/photo.jpg")
        org.junit.jupiter.api.Assertions.assertEquals("/sdcard/相册/photo.jpg", received)
    }
}
