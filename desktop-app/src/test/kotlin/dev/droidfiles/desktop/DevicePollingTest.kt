package dev.droidfiles.desktop

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePollingTest {
    @Test
    fun `device polling publishes newly attached devices and survives a failed scan`(): Unit = runBlocking {
        var scans = 0
        val updates = mutableListOf<List<String>>()
        val completed = CompletableDeferred<Unit>()
        val job = launch {
            pollDevices(intervalMillis = 10, fetch = {
                scans++
                when (scans) {
                    1 -> emptyList()
                    2 -> error("adb temporarily unavailable")
                    else -> listOf("emulator-5554", "device-new")
                }
            }, onUpdate = {
                updates += it
                if ("device-new" in it) completed.complete(Unit)
            })
        }
        withTimeout(1_000) { completed.await() }
        job.cancelAndJoin()
        assertEquals(emptyList<String>(), updates.first())
        assertTrue(updates.last().contains("device-new"))
        assertEquals(3, scans)
    }
}
