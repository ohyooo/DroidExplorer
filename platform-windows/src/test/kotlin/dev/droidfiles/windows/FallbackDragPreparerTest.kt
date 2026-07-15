package dev.droidfiles.windows

import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FallbackDragPreparerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `prepares nested files atomically under cache root`(): Unit = runBlocking {
        val preparer = FallbackDragPreparer.forTest { remote, local, size, _, progress ->
            Files.writeString(local, remote.value)
            progress(size)
        }
        val items = listOf(
            VirtualDragItem("folder", RemotePath.of("/folder"), 0, true, 0),
            VirtualDragItem("folder/file.txt", RemotePath.of("/folder/file.txt"), 16, false, 0),
            VirtualDragItem("single.txt", RemotePath.of("/single.txt"), 11, false, 0),
        )
        val progress = mutableListOf<Pair<Long, Long>>()
        val roots = preparer.prepare(items, temp) { done, total -> progress += done to total }
        assertEquals("/folder/file.txt", Files.readString(temp.resolve("folder/file.txt")))
        assertEquals(setOf(temp.resolve("folder"), temp.resolve("single.txt")), roots.toSet())
        assertEquals(27L to 27L, progress.last())
    }

    @Test
    fun `rejects traversal before writing`(): Unit = runBlocking {
        val preparer = FallbackDragPreparer.forTest { _, _, _, _, _ -> fail("must not download") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { preparer.prepare(listOf(VirtualDragItem("../escape", RemotePath.of("/x"), 1, false, 0)), temp) }
        }
        assertFalse(Files.exists(temp.parent.resolve("escape")))
    }
}
