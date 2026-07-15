package dev.droidfiles.windows

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowsFileIconProviderTest {
    @Test
    fun `cache is shared by case-insensitive extension`() {
        var loads = 0
        val expected = byteArrayOf(1, 2, 3)
        val provider = WindowsFileIconProvider { _, _, _ -> loads++; expected }

        assertArrayEquals(expected, provider.loadPng("one.TXT", false))
        assertArrayEquals(expected, provider.loadPng("two.txt", false))
        assertEquals(1, loads)
    }

    @Test
    fun `directories and extensionless files use stable separate keys`() {
        assertEquals("directory:20", fileIconCacheKey("Pictures", true, 20))
        assertEquals("file::20", fileIconCacheKey("README", false, 20))
        assertEquals("file:.png:32", fileIconCacheKey("photo.PNG", false, 32))
    }

    @Test
    fun `windows shell returns a png for a registered file type`() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val loaded = WindowsFileIconProvider().loadPng("document.txt", false, 20)
        assertNotNull(loaded)
        val png = checkNotNull(loaded)
        assertTrue(png.size > 8)
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), png.copyOf(8))
    }
}
