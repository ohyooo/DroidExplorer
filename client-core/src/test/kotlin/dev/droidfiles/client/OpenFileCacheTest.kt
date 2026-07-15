package dev.droidfiles.client

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class OpenFileCacheTest {
    @Test
    fun `commit hits matching fingerprint and rejects changed version`() {
        val root = Files.createTempDirectory("cache");
        val cache = OpenFileCache(root);
        val fp = RemoteFingerprint(3, 10);
        val path = cache.commit("device", "/a", "a.txt", fp) { Files.write(it, byteArrayOf(1, 2, 3)) }; assertEquals(path, cache.find("device", "/a", fp)); assertNull(cache.find("device", "/a", fp.copy(modifiedMillis = 11))); root.toFile().deleteRecursively()
    }

    @Test
    fun `lru does not delete acquired file`() {
        val root = Files.createTempDirectory("cache");
        val cache = OpenFileCache(root, maxBytes = 3);
        val a = cache.commit("d", "/a", "a", RemoteFingerprint(3, 1)) { Files.write(it, ByteArray(3)) };
        val lease = cache.acquire(a);
        val b = cache.commit("d", "/b", "b", RemoteFingerprint(3, 1)) { Files.write(it, ByteArray(3)) }; assertTrue(Files.exists(a)); assertFalse(Files.exists(b)); lease.close(); cache.clean(); root.toFile().deleteRecursively()
    }
}
