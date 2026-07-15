package dev.droidfiles.desktop

import dev.droidfiles.client.*
import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class TabState(val id: String = UUID.randomUUID().toString(), val path: RemotePath = RemotePath.of("/sdcard"), val back: List<RemotePath> = emptyList(), val forward: List<RemotePath> = emptyList(), val entries: List<RemoteEntry> = emptyList(), val selected: Set<RemotePath> = emptySet(), val selectionAnchor: RemotePath? = null, val loading: Boolean = false, val error: String? = null)
data class ExplorerState(val tabs: List<TabState> = listOf(TabState()), val activeTabId: String = tabs.first().id, val closedTabs: List<TabState> = emptyList())
enum class ExplorerShortcut { NEW_TAB, CLOSE_TAB, RESTORE_TAB, BACK, FORWARD, UP, REFRESH, SELECT_ALL }
class ExplorerViewModel(private val fs: RemoteFileSystem, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(ExplorerState());
    val state: StateFlow<ExplorerState> = mutable.asStateFlow();
    private val navigation = mutableMapOf<String, Job>();
    private var watcher: Job? = null
    fun restoreTabs(paths: List<RemotePath>, activeIndex: Int = 0) {
        navigation.values.forEach { it.cancel() }; navigation.clear();
        val tabs = paths.take(32).ifEmpty { listOf(RemotePath.of("/sdcard")) }.map { TabState(path = it) };
        val index = activeIndex.coerceIn(0, tabs.lastIndex); mutable.value = ExplorerState(tabs = tabs, activeTabId = tabs[index].id); tabs.forEach { load(it, it.path, false) }
    }

    fun startWatching(intervalMillis: Long = 5_000) {
        require(intervalMillis >= 100); watcher?.cancel(); watcher = scope.launch {
            while (isActive) {
                delay(intervalMillis);
                val tab = active(); if (navigation[tab.id]?.isActive == true) continue; runCatching {
                    val latest = mutableListOf<RemoteEntry>(); fs.listDirectory(tab.path).collect { latest += it.entries };
                    val current = find(tab.id); if (current != null && current.path == tab.path && current.entries != latest) replace(current.copy(entries = latest, selected = current.selected.intersect(latest.mapTo(hashSetOf()) { it.path }), error = null))
                }.onFailure { e -> if (e !is CancellationException) find(tab.id)?.takeIf { it.path == tab.path }?.let { replace(it.copy(error = e.message)) } }
            }
        }
    }

    fun close() {
        watcher?.cancel(); watcher = null; navigation.values.forEach { it.cancel() }; navigation.clear()
    }

    fun navigate(path: RemotePath, history: Boolean = true) {
        val tab = active(); navigation.remove(tab.id)?.cancel(); replace(tab.copy(path = path, back = if (history) tab.back + tab.path else tab.back, forward = if (history) emptyList() else tab.forward, entries = emptyList(), selected = emptySet(), loading = true, error = null)); navigation[tab.id] =
            scope.launch { runCatching { fs.listDirectory(path).collect { batch -> val current = find(tab.id) ?: return@collect; if (current.path == path) replace(current.copy(entries = current.entries + batch.entries, loading = !batch.complete)) } }.onFailure { e -> if (e !is CancellationException) find(tab.id)?.let { replace(it.copy(loading = false, error = e.message)) } } }
    }

    private fun load(tab: TabState, path: RemotePath, history: Boolean) {
        val activeId = mutable.value.activeTabId; mutable.value = mutable.value.copy(activeTabId = tab.id); navigate(path, history); mutable.value = mutable.value.copy(activeTabId = activeId)
    }

    fun back() {
        val t = active(); t.back.lastOrNull()?.let { target -> replace(t.copy(back = t.back.dropLast(1), forward = listOf(t.path) + t.forward)); navigate(target, false) }
    }

    fun forward() {
        val t = active(); t.forward.firstOrNull()?.let { target -> replace(t.copy(back = t.back + t.path, forward = t.forward.drop(1))); navigate(target, false) }
    }

    fun newTab(path: RemotePath = active().path) {
        val tab = TabState(path = path); mutable.value = mutable.value.copy(tabs = mutable.value.tabs + tab, activeTabId = tab.id); navigate(path, false)
    }

    fun closeTab(id: String = mutable.value.activeTabId) {
        if (mutable.value.tabs.size == 1) return;
        val tab = find(id) ?: return; navigation.remove(id)?.cancel();
        val tabs = mutable.value.tabs.filterNot { it.id == id }; mutable.value = mutable.value.copy(tabs = tabs, activeTabId = if (id == mutable.value.activeTabId) tabs.last().id else mutable.value.activeTabId, closedTabs = (mutable.value.closedTabs + tab).takeLast(10))
    }

    fun restoreClosed() {
        val tab = mutable.value.closedTabs.lastOrNull() ?: return; mutable.value = mutable.value.copy(tabs = mutable.value.tabs + tab, activeTabId = tab.id, closedTabs = mutable.value.closedTabs.dropLast(1)); navigate(tab.path, false)
    }

    fun activate(id: String) {
        if (find(id) != null) mutable.value = mutable.value.copy(activeTabId = id)
    }

    fun select(path: RemotePath, additive: Boolean = false, range: Boolean = false) {
        val t = active();
        val selected = if (range && t.selectionAnchor != null) {
            val a = t.entries.indexOfFirst { it.path == t.selectionAnchor };
            val b = t.entries.indexOfFirst { it.path == path }; if (a >= 0 && b >= 0) t.entries.subList(minOf(a, b), maxOf(a, b) + 1).mapTo(linkedSetOf()) { it.path } else setOf(path)
        } else if (additive) t.selected xor path else setOf(path); replace(t.copy(selected = selected, selectionAnchor = if (range) t.selectionAnchor ?: path else path))
    }

    fun selectAll() {
        val t = active(); replace(t.copy(selected = t.entries.mapTo(linkedSetOf()) { it.path }, selectionAnchor = t.entries.firstOrNull()?.path))
    }

    fun shortcut(value: ExplorerShortcut) {
        when (value) {
            ExplorerShortcut.NEW_TAB -> newTab(); ExplorerShortcut.CLOSE_TAB -> closeTab(); ExplorerShortcut.RESTORE_TAB -> restoreClosed(); ExplorerShortcut.BACK -> back(); ExplorerShortcut.FORWARD -> forward(); ExplorerShortcut.UP -> {
            val t = active(); if (t.path.value != "/") navigate(RemotePath.of(t.path.value.substringBeforeLast('/').ifEmpty { "/" }))
        }; ExplorerShortcut.REFRESH -> navigate(active().path, false); ExplorerShortcut.SELECT_ALL -> selectAll()
        }
    }

    private infix fun Set<RemotePath>.xor(path: RemotePath) = if (path in this) this - path else this + path
    private fun active() = find(mutable.value.activeTabId)!!;
    private fun find(id: String) = mutable.value.tabs.firstOrNull { it.id == id };
    private fun replace(tab: TabState) {
        mutable.value = mutable.value.copy(tabs = mutable.value.tabs.map { if (it.id == tab.id) tab else it })
    }
}
