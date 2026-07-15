package dev.droidfiles.client

import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConflictResolverTest {
    @Test
    fun `skip omits existing target and replace keeps it`(): Unit = runBlocking { val target = RemotePath.of("/x/a.txt"); assertNull(ConflictResolver.resolve(target, ConflictPolicy.SKIP) { true }); assertEquals(target, ConflictResolver.resolve(target, ConflictPolicy.REPLACE) { true }) }

    @Test
    fun `keep both preserves extension and advances suffix`(): Unit = runBlocking { val occupied = setOf("/x/a.txt", "/x/a (2).txt"); assertEquals("/x/a (3).txt", ConflictResolver.resolve(RemotePath.of("/x/a.txt"), ConflictPolicy.KEEP_BOTH) { it.value in occupied }?.value) }

    @Test
    fun `unused target is returned without renaming`(): Unit = runBlocking { val target = RemotePath.of("/x/archive"); assertEquals(target, ConflictResolver.resolve(target, ConflictPolicy.KEEP_BOTH) { false }) }
}
