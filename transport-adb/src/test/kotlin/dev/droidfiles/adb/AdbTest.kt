package dev.droidfiles.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdbTest {
    @Test
    fun `shell quote escapes apostrophe`() {
        assertEquals("'a'\\''b'", AdbBootstrap.shellQuote("a'b"))
    }
}

