package dev.droidfiles.desktop

import dev.droidfiles.client.EntryType
import dev.droidfiles.client.RawTransferClient
import dev.droidfiles.client.RemoteEntry
import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Instant

class PreviewEligibilityTest {
    @Test
    fun `unsupported and oversized files never open a transfer socket`(): Unit = runBlocking {
        val server = ServerSocket(0)
        val raw = RawTransferClient("127.0.0.1", server.localPort, "token", 64 * 1024)
        val provider = RemoteImagePreviewProvider(raw, Files.createTempDirectory("preview-test"), maxBytes = 1024)
        assertNull(provider.load(RemoteEntry("movie.mp4", RemotePath.of("/movie.mp4"), EntryType.FILE, 10, Instant.EPOCH)))
        assertNull(provider.load(RemoteEntry("large.png", RemotePath.of("/large.png"), EntryType.FILE, 2048, Instant.EPOCH)))
        server.close()
    }
}
