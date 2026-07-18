package dev.droidfiles.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdbTest {
    @Test
    fun `shell quote escapes apostrophe`() {
        assertEquals("'a'\\''b'", AdbBootstrap.shellQuote("a'b"))
    }

    @Test
    fun `bootstrap records remote pid and stop command targets only that process`() {
        val session = BootstrapSession("id", "token", "socket", "/tmp/server.jar", "/tmp/server.pid")
        val launch = AdbBootstrap.launchCommand(session, 1, 0)
        val stop = AdbBootstrap.stopCommand(session)
        assertEquals(true, launch.startsWith("echo $$ > '/tmp/server.pid'; exec env CLASSPATH='/tmp/server.jar'"))
        assertEquals(true, stop.contains("kill $(cat '/tmp/server.pid')"))
        assertEquals(true, stop.contains("rm -f '/tmp/server.pid'"))
    }
}
