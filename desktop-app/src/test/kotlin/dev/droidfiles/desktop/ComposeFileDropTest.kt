package dev.droidfiles.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ComposeFileDropTest {
    @Test
    fun `Compose file URI payload preserves spaces and Unicode`() {
        val paths = fileUrisToPaths(listOf("file:///C:/Drop/%E4%B8%AD%E6%96%87%20file.txt", "https://example.test/not-local"))
        assertEquals(listOf(Path.of("C:/Drop/中文 file.txt")), paths)
    }
}
