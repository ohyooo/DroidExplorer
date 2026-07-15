package dev.droidfiles.desktop

import dev.droidfiles.client.*
import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ExplorerViewModelTest {
    @Test
    fun `stale navigation cannot overwrite latest path`(): Unit = runBlocking {
        val fs = FakeFs();
        val vm = ExplorerViewModel(fs, this); vm.navigate(RemotePath.of("/slow")); vm.navigate(RemotePath.of("/fast")); delay(100);
        val tab = vm.state.value.tabs.first(); assertEquals("/fast", tab.path.value); assertEquals(listOf("fast"), tab.entries.map { it.name }); assertFalse(tab.loading)
    }

    @Test
    fun `tabs close and restore with independent history`(): Unit = runBlocking {
        val vm = ExplorerViewModel(FakeFs(), this);
        val first = vm.state.value.activeTabId; vm.newTab(RemotePath.of("/second")); delay(30);
        val second = vm.state.value.activeTabId; vm.closeTab(second); assertEquals(first, vm.state.value.activeTabId); vm.restoreClosed(); assertEquals(second, vm.state.value.activeTabId); assertEquals("/second", vm.state.value.tabs.last().path.value)
    }

    @Test
    fun `shortcuts and activation update only active tab`(): Unit = runBlocking {
        val vm = ExplorerViewModel(FakeFs(), this); vm.navigate(RemotePath.of("/first")); delay(30);
        val first = vm.state.value.activeTabId; vm.shortcut(ExplorerShortcut.NEW_TAB); delay(30);
        val second = vm.state.value.activeTabId; vm.shortcut(ExplorerShortcut.SELECT_ALL); assertTrue(vm.state.value.tabs.first { it.id == second }.selected.isNotEmpty()); assertTrue(vm.state.value.tabs.first { it.id == first }.selected.isEmpty()); vm.activate(first); assertEquals(first, vm.state.value.activeTabId); vm.shortcut(ExplorerShortcut.UP); delay(30); assertEquals("/", vm.state.value.tabs.first { it.id == first }.path.value)
    }

    @Test
    fun `ctrl toggles and shift selects anchored range`(): Unit = runBlocking {
        val vm = ExplorerViewModel(ManyFs(), this); vm.navigate(RemotePath.of("/many")); delay(10);
        val entries = vm.state.value.tabs.first().entries; vm.select(entries[1].path); vm.select(entries[3].path, range = true); assertEquals(entries.subList(1, 4).map { it.path }.toSet(), vm.state.value.tabs.first().selected); vm.select(entries[2].path, additive = true); assertFalse(entries[2].path in vm.state.value.tabs.first().selected)
    }

    @Test
    fun `restores persisted tabs and active index`(): Unit = runBlocking { val vm = ExplorerViewModel(FakeFs(), this); vm.restoreTabs(listOf(RemotePath.of("/one"), RemotePath.of("/two")), 1); delay(40); assertEquals(listOf("/one", "/two"), vm.state.value.tabs.map { it.path.value }); assertEquals(vm.state.value.tabs[1].id, vm.state.value.activeTabId) }

    @Test
    fun `active directory watcher refreshes changes and stops on close`(): Unit = runBlocking {
        val fs = ChangingFs();
        val vm = ExplorerViewModel(fs, this); vm.navigate(RemotePath.of("/watched")); delay(20); assertEquals(listOf("one"), vm.state.value.tabs.first().entries.map { it.name }); vm.startWatching(100); fs.names = listOf("one", "two"); delay(140); assertEquals(listOf("one", "two"), vm.state.value.tabs.first().entries.map { it.name });
        val calls = fs.calls; vm.close(); delay(140); assertEquals(calls, fs.calls)
    }

    private class ChangingFs : RemoteFileSystem {
        var names = listOf("one");
        var calls = 0;
        override fun listDirectory(path: RemotePath) = flowOf(DirectoryBatch(names.map { RemoteEntry(it, path.child(it), EntryType.FILE, 1, Instant.EPOCH) }, true)).also { calls++ };
        override suspend fun stat(path: RemotePath, followLinks: Boolean) = error("unused");
        override suspend fun mkdir(path: RemotePath, parents: Boolean) = Unit;
        override suspend fun rename(source: RemotePath, target: RemotePath, policy: ConflictPolicy) = Unit;
        override suspend fun copy(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
        override suspend fun move(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
        override suspend fun delete(paths: List<RemotePath>, recursive: Boolean) = OperationHandle("x");
        override suspend fun computeHash(path: RemotePath) = ""
    }

    private class ManyFs : RemoteFileSystem {
        override fun listDirectory(path: RemotePath) = flowOf(DirectoryBatch((1..5).map { RemoteEntry("$it", path.child("$it"), EntryType.FILE, 1, Instant.EPOCH) }, true));
        override suspend fun stat(path: RemotePath, followLinks: Boolean) = error("unused");
        override suspend fun mkdir(path: RemotePath, parents: Boolean) = Unit;
        override suspend fun rename(source: RemotePath, target: RemotePath, policy: ConflictPolicy) = Unit;
        override suspend fun copy(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
        override suspend fun move(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
        override suspend fun delete(paths: List<RemotePath>, recursive: Boolean) = OperationHandle("x");
        override suspend fun computeHash(path: RemotePath) = ""
    }

    private class FakeFs : RemoteFileSystem {
        override fun listDirectory(path: RemotePath) = flow {
            if (path.value == "/slow") delay(500) else delay(10);
            val name = path.value.substringAfterLast('/'); emit(DirectoryBatch(listOf(RemoteEntry(name, path.child(name), EntryType.FILE, 1, Instant.EPOCH)), true))
        };

        override suspend fun stat(path: RemotePath, followLinks: Boolean) = error("unused");
        override suspend fun mkdir(path: RemotePath, parents: Boolean) = Unit;
        override suspend fun rename(source: RemotePath, target: RemotePath, policy: ConflictPolicy) = Unit;
        override suspend fun copy(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
        override suspend fun move(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
        override suspend fun delete(paths: List<RemotePath>, recursive: Boolean) = OperationHandle("x");
        override suspend fun computeHash(path: RemotePath) = ""
    }
}
