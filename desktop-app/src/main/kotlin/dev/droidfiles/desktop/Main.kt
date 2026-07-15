package dev.droidfiles.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.droidfiles.adb.*
import dev.droidfiles.client.*
import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import dev.droidfiles.windows.WindowsPlatformShell
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import dev.droidfiles.windows.WindowsVirtualDragSession
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.droidfiles.privilege.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import org.jetbrains.skia.Image as SkiaImage
import dev.droidfiles.windows.FileIconProvider
import dev.droidfiles.windows.WindowsFileIconProvider

private val ExplorerColors = lightColorScheme(primary = Color(0xFF0067C0), onPrimary = Color.White, background = Color(0xFFF3F3F3), surface = Color(0xFFFBFBFB), surfaceVariant = Color(0xFFF0F0F0), secondaryContainer = Color(0xFFDCEBFA), outline = Color(0xFFD0D0D0), error = Color(0xFFC42B1C))
fun main() = application { Window(onCloseRequest = ::exitApplication, title = "DroidFiles") { MaterialTheme(colorScheme = ExplorerColors) { DroidFilesApp(window) } } }

@OptIn(ExperimentalComposeUiApi::class)

@Composable
private fun DroidFilesApp(window: java.awt.Window) {
    val scope = rememberCoroutineScope();
    val fileIconProvider = remember { WindowsFileIconProvider() };
    val adb = remember { AdbClient(AdbLocator().locate()) };
    val settingsStore = remember { SettingsStore() };
    val privilegeStore = remember { PrivilegeSettingsStore() };
    var settings by remember { mutableStateOf(settingsStore.load()) };
    val cache = remember { OpenFileCache(Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), AppIdentity.NAME, "cache", "open"), settings.cacheMaxBytes, settings.cacheMaxDays * 24L * 60 * 60 * 1000) };
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) };
    var session by remember { mutableStateOf<ConnectedAdbSession?>(null) };
    var explorer by remember { mutableStateOf<ExplorerViewModel?>(null) };
    var explorerState by remember { mutableStateOf<ExplorerState?>(null) };
    var connectedSerial by remember { mutableStateOf<String?>(null) };
    var showSettings by remember { mutableStateOf(false) };
    var showPrivilegeSettings by remember { mutableStateOf(System.getenv("DROIDFILES_UI_TEST_PAGE") == "privilege") };
    var showAssociationSettings by remember { mutableStateOf(System.getenv("DROIDFILES_UI_TEST_PAGE") == "associations") };
    var privilegePreferences by remember { mutableStateOf(privilegeStore.load()) };
    var opener by remember { mutableStateOf<RemoteFileOpenService?>(null) };
    var transfers by remember { mutableStateOf<DefaultTransferManager?>(null) };
    var transferJobs by remember { mutableStateOf<List<TransferJobSnapshot>>(emptyList()) };
    var path by remember { mutableStateOf(RemotePath.of("/sdcard")) };
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) };
    var selected by remember { mutableStateOf<RemoteEntry?>(null) };
    var dragManifest by remember { mutableStateOf<List<dev.droidfiles.windows.VirtualDragItem>>(emptyList()) };
    var preparingDrag by remember { mutableStateOf(false) };
    var loading by remember { mutableStateOf(false) };
    var error by remember { mutableStateOf<String?>(null) }
    var lastDevice by remember { mutableStateOf<AdbDevice?>(null) }
    var previewProvider by remember { mutableStateOf<FilePreviewProvider?>(null) }
    var controlPressed by remember { mutableStateOf(false) }
    var shiftPressed by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var remoteClipboard by remember { mutableStateOf<List<RemotePath>>(emptyList()) }
    var clipboardCut by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var searchRunning by remember { mutableStateOf(false) }
    var searchLimitReached by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var editSession by remember { mutableStateOf<ManagedEditSession?>(null) }
    var editSyncing by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    fun refreshDevices() {
        scope.launch { runCatching { adb.devices() }.onSuccess { devices = it }.onFailure { error = it.message } }
    }

    fun navigate(target: RemotePath) {
        explorer?.let { it.navigate(target); return };
        val active = session ?: return; path = target; entries = emptyList(); loading = true; scope.launch { runCatching { active.fileSystem.listDirectory(target).collect { batch -> entries = entries + batch.entries; loading = !batch.complete } }.onFailure { error = it.message; loading = false } }
    }

    fun connect(device: AdbDevice) {
        lastDevice = device; loading = true; error = null
        scope.launch {
            runCatching {
                val resource = checkNotNull(object {}.javaClass.getResourceAsStream("/server/droidfiles-server.jar"))
                val jar = withContext(Dispatchers.IO) { Files.createTempFile("droidfiles-server", ".jar").also { Files.copy(resource, it, java.nio.file.StandardCopyOption.REPLACE_EXISTING); it.toFile().deleteOnExit() } }
                val plan = PrivilegeSessionPlan(privilegeStore.load().forDevice(device.serial)); plan.prepare(adb, device.serial)
                ConnectedAdbSession.connect(adb, device.serial, jar, scope, plan::wrap, plan::validate)
            }.onSuccess { established ->
                explorer?.close(); opener?.close(); transfers?.close(); session?.close(); session = established; connectedSerial = device.serial
                opener = RemoteFileOpenService(device.serial, established.fileSystem, established.rawTransfers, cache, WindowsPlatformShell()) { settings }
                val previewDir = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), AppIdentity.NAME, "cache", "preview", device.serial.hashCode().toUInt().toString(16))
                previewProvider = RemoteImagePreviewProvider(established.rawTransfers, previewDir)
                transfers = DefaultTransferManager(established.rawTransfers, established.fileSystem, scope).also { manager -> scope.launch { manager.jobs.collect { transferJobs = it } } }
                explorer = ExplorerViewModel(established.fileSystem, scope).also { vm ->
                    scope.launch {
                        vm.state.collect { state ->
                            explorerState = state
                            state.tabs.firstOrNull { tab -> tab.id == state.activeTabId }?.let { tab ->
                                path = tab.path; entries = tab.entries; loading = tab.loading; error = tab.error
                            }
                            val tabPaths = state.tabs.map { it.path.value }
                            val activeIndex = state.tabs.indexOfFirst { it.id == state.activeTabId }.coerceAtLeast(0)
                            if (tabPaths != settings.lastTabs || activeIndex != settings.activeTabIndex) {
                                val updated = settings.copy(lastTabs = tabPaths, activeTabIndex = activeIndex)
                                settings = updated
                                withContext(Dispatchers.IO) { settingsStore.save(updated) }
                            }
                        }
                    }
                    val restored = settings.lastTabs.mapNotNull { saved -> runCatching { RemotePath.of(saved) }.getOrNull() }
                    vm.restoreTabs(restored, settings.activeTabIndex)
                    vm.startWatching()
                }
                loading = false
                scope.launch {
                    established.connection.state.first { it == ConnectionState.DISCONNECTED }; if (session === established) {
                    withContext(Dispatchers.IO) { established.close() }; searchJob?.cancel(); searchRunning = false; session = null; opener?.close(); opener = null; transfers?.close(); transfers = null; previewProvider = null; explorer?.close(); explorer = null; loading = false; error = "Connection lost. Reconnect to continue."
                }
                }
            }.onFailure { error = it.message; loading = false }
        }
    }
    DisposableEffect(Unit) { refreshDevices(); onDispose { searchJob?.cancel(); explorer?.close(); opener?.close(); transfers?.close(); session?.close() } }
    var autoConnectAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(devices, session) { val serial = System.getenv("DROIDFILES_UI_TEST_CONNECT"); if (session == null && serial != null && !autoConnectAttempted) devices.firstOrNull { it.serial == serial }?.let { autoConnectAttempted = true; connect(it) } }
    LaunchedEffect(selected?.path, session) {
        dragManifest = emptyList();
        val item = selected;
        val active = session; if (item != null && active != null) {
        preparingDrag = true; runCatching { VirtualDragManifestBuilder(active.fileSystem).build(listOf(item)) }.onSuccess { dragManifest = it }.onFailure { error = it.message }; preparingDrag = false
    }
    }
    val activeTab = explorerState?.tabs?.firstOrNull { it.id == explorerState?.activeTabId }
    val selectedPaths = activeTab?.selected ?: selected?.let { setOf(it.path) }.orEmpty()
    val currentFileDrop by rememberUpdatedState<(List<Path>) -> Unit> { files ->
        transfers?.let { manager -> scope.launch { runCatching { manager.upload(files, path, TransferOptions()) }.onFailure { error = it.message } } }
    }
    val fileDropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = runCatching { (event.dragData() as? DragData.FilesList)?.readFiles()?.let(::fileUrisToPaths) }.getOrNull().orEmpty()
                if (files.isEmpty()) return false
                currentFileDrop(files)
                return true
            }
        }
    }
    DisposableEffect(window, explorer, explorerState, session, path, remoteClipboard, clipboardCut) {
        val manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = java.awt.KeyEventDispatcher { event ->
            if (manager.activeWindow != window) return@KeyEventDispatcher false
            controlPressed = event.isControlDown; shiftPressed = event.isShiftDown
            if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            val shortcut = when {
                event.isControlDown && event.isShiftDown && event.keyCode == java.awt.event.KeyEvent.VK_T -> ExplorerShortcut.RESTORE_TAB
                event.isControlDown && event.keyCode == java.awt.event.KeyEvent.VK_T -> ExplorerShortcut.NEW_TAB
                event.isControlDown && event.keyCode == java.awt.event.KeyEvent.VK_W -> ExplorerShortcut.CLOSE_TAB
                event.isControlDown && event.keyCode == java.awt.event.KeyEvent.VK_A -> ExplorerShortcut.SELECT_ALL
                event.isAltDown && event.keyCode == java.awt.event.KeyEvent.VK_LEFT -> ExplorerShortcut.BACK
                event.isAltDown && event.keyCode == java.awt.event.KeyEvent.VK_RIGHT -> ExplorerShortcut.FORWARD
                event.isAltDown && event.keyCode == java.awt.event.KeyEvent.VK_UP -> ExplorerShortcut.UP
                event.keyCode == java.awt.event.KeyEvent.VK_F5 -> ExplorerShortcut.REFRESH
                else -> null
            }
            if (shortcut != null) {
                explorer?.shortcut(shortcut); return@KeyEventDispatcher true
            }
            val current = explorerState?.tabs?.firstOrNull { it.id == explorerState?.activeTabId }
            val currentSelection = current?.selected.orEmpty()
            when {
                event.isControlDown && event.keyCode == java.awt.event.KeyEvent.VK_F -> {
                    showSearch = true; true
                }

                event.isControlDown && event.keyCode == java.awt.event.KeyEvent.VK_C -> {
                    remoteClipboard = currentSelection.toList(); clipboardCut = false; true
                }

                event.isControlDown && event.keyCode == java.awt.event.KeyEvent.VK_X -> {
                    remoteClipboard = currentSelection.toList(); clipboardCut = true; true
                }

                event.isControlDown && event.keyCode == java.awt.event.KeyEvent.VK_V && remoteClipboard.isNotEmpty() -> {
                    val active = session ?: return@KeyEventDispatcher false; scope.launch {
                        runCatching { if (clipboardCut) active.fileSystem.move(remoteClipboard, path, ConflictPolicy.KEEP_BOTH) else active.fileSystem.copy(remoteClipboard, path, ConflictPolicy.KEEP_BOTH) }.onSuccess {
                            if (clipboardCut) {
                                remoteClipboard = emptyList(); clipboardCut = false
                            }; navigate(path)
                        }.onFailure { error = it.message }
                    }; true
                }

                event.keyCode == java.awt.event.KeyEvent.VK_F2 && currentSelection.size == 1 -> {
                    entries.firstOrNull { it.path == currentSelection.first() }?.let { renameTarget = it; renameValue = it.name }; true
                }

                event.keyCode == java.awt.event.KeyEvent.VK_ENTER && currentSelection.size == 1 -> {
                    entries.firstOrNull { it.path == currentSelection.first() }?.let { item -> if (item.type == EntryType.DIRECTORY) navigate(item.path) else scope.launch { runCatching { opener?.open(item.path) }.onFailure { error = it.message } } }; true
                }

                event.keyCode == java.awt.event.KeyEvent.VK_DELETE && currentSelection.isNotEmpty() -> {
                    val active = session ?: return@KeyEventDispatcher false; scope.launch { runCatching { active.fileSystem.delete(currentSelection.toList(), true); navigate(path) }.onFailure { error = it.message } }; true
                }

                else -> false
            }
        }
        manager.addKeyEventDispatcher(dispatcher); onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }
    Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { event -> runCatching { event.dragData() is DragData.FilesList }.getOrDefault(false) }, target = fileDropTarget)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ExplorerTabStrip(explorerState, session?.hello?.model ?: "Devices", onSelect = { explorer?.activate(it) }, onNew = { explorer?.newTab(path) }, onClose = { explorer?.closeTab(it) }); ExplorerCommandBar(canOperate = session != null, hasSelection = selectedPaths.isNotEmpty(), onNewFolder = {
            val active = session ?: return@ExplorerCommandBar; scope.launch {
            val base = "New folder";
            var candidate = path.child(base);
            var suffix = 2; while (runCatching { active.fileSystem.stat(candidate) }.isSuccess) {
            candidate = path.child("$base ($suffix)"); suffix++
        }; runCatching { active.fileSystem.mkdir(candidate); navigate(path) }.onFailure { error = it.message }
        }
        }, onDelete = {
            if (selectedPaths.isEmpty()) return@ExplorerCommandBar;
            val active = session ?: return@ExplorerCommandBar; scope.launch { runCatching { active.fileSystem.delete(selectedPaths.toList(), true); selected = null; navigate(path) }.onFailure { error = it.message } }
        }, onRefresh = { navigate(path) }, onSearch = { showSearch = true }, onSettings = { showSettings = true }); ExplorerNavigationBar(
            path = path,
            connected = session != null,
            canBack = activeTab?.back?.isNotEmpty() == true,
            canForward = activeTab?.forward?.isNotEmpty() == true,
            onPathChange = { runCatching { path = RemotePath.of(it) } },
            onNavigate = { navigate(path) },
            onBack = { explorer?.back() },
            onForward = { explorer?.forward() },
            onUp = { val parent = path.value.substringBeforeLast('/').ifEmpty { "/" }; navigate(RemotePath.of(parent)) })
            error?.let { message ->
                Surface(color = Color(0xFFFFE8E6)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(vertical = 8.dp)); if (session == null && lastDevice != null) TextButton(
                        onClick = { connect(lastDevice!!) },
                        modifier = Modifier.testTag("reconnect")
                    ) { Text("Reconnect") }
                    }
                }
            }; Row(Modifier.fillMaxSize()) {
            ExplorerSidebar(devices, session?.hello?.model, transferJobs, onDevice = { connect(it) }, onLocation = { navigate(it) }, onTransferAction = { id, action ->
                scope.launch {
                    when (action) {
                        "pause" -> transfers?.pause(id); "resume" -> transfers?.resume(id); "retry" -> transfers?.retry(id); else -> transfers?.cancel(id)
                    }
                }
            }); VerticalDivider(); Column(Modifier.weight(1f).background(Color.White)) {
            Row(Modifier.fillMaxWidth().height(38.dp).background(Color(0xFFF7F7F7)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Name", Modifier.weight(1f)); Text("Date modified", Modifier.width(180.dp)); Text("Type", Modifier.width(120.dp)); Text(
                "Size",
                Modifier.width(100.dp)
            )
            }; HorizontalDivider(); if (loading || preparingDrag) LinearProgressIndicator(Modifier.fillMaxWidth()); LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.path.value }) { entry ->
                ExplorerFileRow(
                    entry,
                    entry.path in selectedPaths,
                    onSelect = { selected = entry; explorer?.select(entry.path, additive = controlPressed, range = shiftPressed) },
                    onOpen = { if (entry.type == EntryType.DIRECTORY) navigate(entry.path) else scope.launch { runCatching { opener?.open(entry.path) }.onFailure { error = it.message } } },
                    onRename = { renameTarget = entry; renameValue = entry.name },
                    onDelete = { val active = session ?: return@ExplorerFileRow; scope.launch { runCatching { active.fileSystem.delete(listOf(entry.path), true); navigate(path) }.onFailure { error = it.message } } },
                    onDrag = {
                        if (entry.path in selectedPaths && dragManifest.isNotEmpty()) {
                            val active = session ?: return@ExplorerFileRow; scope.launch { runCatching { WindowsVirtualDragSession(dragManifest, active.rawTransfers, scope).use { it.begin() } }.onFailure { error = it.message } }
                        }
                    },
                    onEdit = { scope.launch { runCatching { opener?.beginEdit(entry.path) }.onSuccess { session -> if (session != null) editSession = session }.onFailure { error = it.message } } },
                    onCopyFile = { scope.launch { runCatching { checkNotNull(opener).download(entry.path) }.onSuccess { local -> clipboard.setClipEntry(fileListClipEntry(listOf(local))) }.onFailure { error = it.message } } },
                    iconProvider = fileIconProvider,
                    previewProvider = previewProvider
                )
            }
        }; HorizontalDivider(); Row(Modifier.fillMaxWidth().height(32.dp).background(Color(0xFFF7F7F7)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (selectedPaths.isEmpty()) "${entries.size} items" else "${entries.size} items · ${selectedPaths.size} selected", Modifier.weight(1f)); Text(session?.hello?.let { "${it.model} · ${it.selinuxContext}" } ?: "Not connected") }
        }
        }
            renameTarget?.let { item ->
                AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("Rename") }, text = { OutlinedTextField(renameValue, { renameValue = it }, singleLine = true, label = { Text("Name") }) }, confirmButton = {
                    Button(enabled = renameValue.isNotBlank() && renameValue != item.name, onClick = {
                        val active = session ?: return@Button;
                        val parent = RemotePath.of(item.path.value.substringBeforeLast('/').ifEmpty { "/" });
                        val target = runCatching { parent.child(renameValue) }.getOrElse { error = it.message; return@Button }; scope.launch { runCatching { active.fileSystem.rename(item.path, target, ConflictPolicy.SKIP); renameTarget = null; navigate(path) }.onFailure { error = it.message } }
                    }) { Text("Rename") }
                }, dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } })
            }
            if (showSettings) SettingsHubDialog(onDismiss = { showSettings = false }, onPrivilege = { showSettings = false; showPrivilegeSettings = true }, onAssociations = { showSettings = false; showAssociationSettings = true })
            if (showPrivilegeSettings) PrivilegeSettingsDialog(privilegePreferences, connectedSerial, onDismiss = { showPrivilegeSettings = false }, onSave = { value -> privilegeStore.save(value); privilegePreferences = value; showPrivilegeSettings = false })
            if (showAssociationSettings) AssociationSettingsDialog(settings, onDismiss = { showAssociationSettings = false }, onSave = { value -> settingsStore.save(value); settings = value; showAssociationSettings = false })
            if (showSearch) RemoteSearchDialog(
                searchQuery,
                searchResults,
                searchRunning,
                searchLimitReached,
                onQueryChange = { searchQuery = it },
                onSearch = { query ->
                    val active = session ?: return@RemoteSearchDialog; searchJob?.cancel(); searchResults = emptyList(); searchLimitReached = false; searchRunning = true; searchJob =
                    scope.launch { runCatching { RemoteSearchService(active.fileSystem).search(path, query).collect { snapshot -> searchResults = snapshot.results; searchRunning = !snapshot.complete; searchLimitReached = snapshot.limitReached } }.onFailure { e -> if (e !is CancellationException) error = e.message; searchRunning = false } }
                },
                onOpen = { entry -> showSearch = false; if (entry.type == EntryType.DIRECTORY) navigate(entry.path) else scope.launch { runCatching { opener?.open(entry.path) }.onFailure { error = it.message } } },
                onDismiss = { searchJob?.cancel(); searchRunning = false; showSearch = false })
            editSession?.let { edit -> ManagedEditSyncDialog(edit, editSyncing, onSync = { editSyncing = true; scope.launch { runCatching { checkNotNull(opener).syncBack(edit) }.onSuccess { editSession = null; navigate(path) }.onFailure { error = it.message }; editSyncing = false } }, onDismiss = { if (!editSyncing) editSession = null }) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ExplorerFileRow(entry: RemoteEntry, selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onDrag: () -> Unit, onEdit: () -> Unit = onOpen, onCopyFile: () -> Unit = {}, iconProvider: FileIconProvider? = null, previewProvider: FilePreviewProvider? = null) {
    val dragState = rememberDraggableState {}
    val currentSelect by rememberUpdatedState(onSelect)
    val currentOpen by rememberUpdatedState(onOpen)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val iconBytes by produceState<ByteArray?>(null, entry.name, entry.type, iconProvider) { value = iconProvider?.let { withContext(Dispatchers.IO) { it.loadPng(entry.name, entry.type == EntryType.DIRECTORY, 20) } } }
    val previewBytes by produceState<ByteArray?>(null, entry.path, entry.size, entry.modified, previewProvider) {
        if (previewProvider != null) {
            delay(200); value = runCatching { previewProvider.load(entry) }.getOrNull()
        }
    }
    val iconBitmap = remember(iconBytes, previewBytes) { (previewBytes ?: iconBytes)?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } }
    ContextMenuArea(items = {
        buildList {
            add(ContextMenuItem(if (entry.type == EntryType.DIRECTORY) "Open" else "Open with default app", onOpen))
            if (entry.type == EntryType.FILE) add(ContextMenuItem("Edit and sync", onEdit))
            if (entry.type == EntryType.FILE) add(ContextMenuItem("Copy file to clipboard", onCopyFile))
            add(ContextMenuItem("Rename", onRename))
            add(ContextMenuItem("Delete", onDelete))
            add(ContextMenuItem("Copy remote path", { scope.launch { clipboard.setClipEntry(ClipEntry(java.awt.datatransfer.StringSelection(entry.path.value))) } }))
        }
    }) {
        Row(Modifier.testTag("file-row-${entry.path.value}").semantics { onClick("Select") { currentSelect(); true } }.fillMaxWidth().height(36.dp).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.White).pointerInput(entry.path) { detectTapGestures(onTap = { currentSelect() }, onDoubleTap = { currentSelect(); currentOpen() }) }
            .draggable(state = dragState, orientation = Orientation.Horizontal, enabled = selected, onDragStarted = { onDrag() }).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { iconBitmap?.let { Image(it, contentDescription = null, modifier = Modifier.size(20.dp)) } ?: Text(if (entry.type == EntryType.DIRECTORY) "📁" else "📄"); Spacer(Modifier.width(8.dp)); Text(entry.name) }
            Text(entry.modified?.toString()?.replace('T', ' ')?.take(16) ?: "", Modifier.width(180.dp))
            Text(entry.type.name.lowercase().replaceFirstChar { it.uppercase() }, Modifier.width(120.dp))
            Text(if (entry.type == EntryType.FILE) formatSize(entry.size) else "", Modifier.width(100.dp))
        }
    }
}

@Composable
internal fun PrivilegeSettingsDialog(initial: PrivilegePreferences, serial: String?, onDismiss: () -> Unit, onSave: (PrivilegePreferences) -> Unit) {
    var perDevice by remember { mutableStateOf(serial != null && initial.devices.containsKey(serial)) };
    val original = remember(initial, serial) { serial?.let(initial.devices::get) ?: initial.default };
    var backend by remember { mutableStateOf(original.backend) };
    var custom by remember { mutableStateOf(original.customArguments.joinToString("\n")) };
    var dangerous by remember { mutableStateOf(original.allowDangerousSystemPaths) }; AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privilege settings") },
        text = {
            Column(Modifier.width(480.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text("Root is optional. New settings apply to the next connection."); if (serial != null) Row { Checkbox(perDevice, { perDevice = it }, Modifier.testTag("privilege-per-device")); Text("Override for $serial", Modifier.padding(top = 12.dp)) }; PrivilegeBackend.entries.forEach { candidate ->
                Row {
                    RadioButton(
                        backend == candidate,
                        { backend = candidate },
                        Modifier.testTag("privilege-backend-${candidate.name}")
                    ); Text(candidate.name, Modifier.padding(top = 12.dp))
                }
            }; if (backend == PrivilegeBackend.CUSTOM) OutlinedTextField(custom, { custom = it }, Modifier.testTag("privilege-custom-arguments"), label = { Text("One argument per line; include {command}") }, minLines = 3); Row { Checkbox(dangerous, { dangerous = it }, Modifier.testTag("privilege-dangerous-paths")); Text("Allow destructive operations on protected system paths", Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val args = if (backend == PrivilegeBackend.CUSTOM) custom.lines().filter { it.isNotBlank() } else emptyList();
                val config = runCatching { PrivilegeConfig(backend, args, dangerous) }.getOrNull() ?: return@Button; onSave(if (perDevice && serial != null) initial.copy(devices = initial.devices + (serial to config)) else initial.copy(default = config))
            }, modifier = Modifier.testTag("privilege-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("privilege-cancel")) { Text("Cancel") } })
}

@Composable
internal fun SettingsHubDialog(onDismiss: () -> Unit, onPrivilege: () -> Unit, onAssociations: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onPrivilege, modifier = Modifier.fillMaxWidth().testTag("settings-privilege")) { Text("Device and Root permissions") }; OutlinedButton(onClick = onAssociations, modifier = Modifier.fillMaxWidth().testTag("settings-associations")) { Text("File associations") } } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
internal fun AssociationSettingsDialog(initial: AppSettings, onDismiss: () -> Unit, onSave: (AppSettings) -> Unit) {
    val firstKey = initial.associations.keys.sorted().firstOrNull().orEmpty()
    val first = initial.associations[firstKey]
    var extension by remember { mutableStateOf(firstKey) }
    var executable by remember { mutableStateOf(first?.executable.orEmpty()) }
    var arguments by remember { mutableStateOf(first?.arguments?.joinToString("\n").orEmpty()) }
    var workingDirectory by remember { mutableStateOf(first?.workingDirectory.orEmpty()) }
    var waitForExit by remember { mutableStateOf(first?.waitForExit ?: false) }
    fun normalized() = extension.trim().removePrefix(".").lowercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File associations") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial.associations.isNotEmpty()) Text("Configured: ${initial.associations.keys.sorted().joinToString()}")
                OutlinedTextField(extension, { extension = it }, Modifier.fillMaxWidth().testTag("association-extension"), singleLine = true, label = { Text("Extension, for example txt") })
                OutlinedTextField(executable, { executable = it }, Modifier.fillMaxWidth().testTag("association-executable"), singleLine = true, label = { Text("Executable") })
                OutlinedTextField(arguments, { arguments = it }, Modifier.fillMaxWidth().testTag("association-arguments"), label = { Text("One argument per line; use {file}") }, minLines = 3)
                OutlinedTextField(workingDirectory, { workingDirectory = it }, Modifier.fillMaxWidth().testTag("association-working-directory"), singleLine = true, label = { Text("Working directory (optional)") })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(waitForExit, { waitForExit = it }, Modifier.testTag("association-wait")); Text("Wait for program to exit") }
                Text("Use system default removes the custom rule for this extension.")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { val key = normalized(); if (key.isNotEmpty()) onSave(initial.copy(associations = initial.associations - key)) }, modifier = Modifier.testTag("association-default")) { Text("Use system default") }
                Button(enabled = normalized().isNotEmpty() && executable.isNotBlank(), onClick = {
                    val key = normalized()
                    val args = arguments.lines().filter { it.isNotBlank() }.ifEmpty { listOf("{file}") }
                    val directory = workingDirectory.trim().takeIf { it.isNotEmpty() }
                    onSave(initial.copy(associations = initial.associations + (key to ProgramAssociation(executable.trim(), args, directory, waitForExit))))
                }, modifier = Modifier.testTag("association-save")) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExplorerTabStrip(state: ExplorerState?, title: String, onSelect: (String) -> Unit, onNew: () -> Unit, onClose: (String) -> Unit) {
    val tabs = state?.tabs ?: listOf(TabState());
    val active = state?.activeTabId ?: tabs.first().id; Surface(color = Color(0xFFF3F3F3)) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(start = 10.dp, top = 6.dp), verticalAlignment = Alignment.Bottom) {
            tabs.forEach { tab ->
                Surface(
                    onClick = { onSelect(tab.id) },
                    color = if (tab.id == active) Color(0xFFFBFBFB) else Color.Transparent,
                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                    modifier = Modifier.width(200.dp).fillMaxHeight()
                ) { Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("📱", Modifier.padding(end = 7.dp)); Text(if (state == null) title else tab.path.value.substringAfterLast('/').ifEmpty { "/" }, Modifier.weight(1f), maxLines = 1); if (tabs.size > 1) Text("×", Modifier.clickable { onClose(tab.id) }.padding(4.dp), color = Color(0xFF666666)) } }
            }; TextButton(onClick = onNew, enabled = state != null) { Text("＋", color = Color(0xFF444444)) }
        }
    }
}

@Composable
private fun ExplorerCommandBar(canOperate: Boolean, hasSelection: Boolean, onNewFolder: () -> Unit, onDelete: () -> Unit, onRefresh: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    Surface(color = Color(0xFFFBFBFB)) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CommandButton("＋", "New folder", canOperate, onNewFolder); VerticalDivider(Modifier.height(24.dp).padding(horizontal = 5.dp)); CommandButton("↻", "Refresh", canOperate, onRefresh); CommandButton(
            "⌕",
            "Search",
            canOperate,
            onSearch
        ); CommandButton("🗑", "Delete", canOperate && hasSelection, onDelete); Spacer(Modifier.weight(1f)); CommandButton("⚙", "Settings", true, onSettings)
        }
    }
}

@Composable
private fun CommandButton(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(5.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("$icon  $label", color = if (enabled) Color(0xFF202020) else Color(0xFF999999)) }
}

@Composable
private fun ExplorerNavigationBar(path: RemotePath, connected: Boolean, canBack: Boolean, canForward: Boolean, onPathChange: (String) -> Unit, onNavigate: () -> Unit, onBack: () -> Unit, onForward: () -> Unit, onUp: () -> Unit) {
    Surface(color = Color(0xFFFBFBFB)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            CommandButton("←", "", canBack, onBack); CommandButton("→", "", canForward, onForward); CommandButton("↑", "", connected && path.value != "/", onUp); OutlinedTextField(
            path.value,
            onPathChange,
            Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(5.dp)
        ); CommandButton("→", "Go", connected, onNavigate)
        }
    }
}

@Composable
private fun ExplorerSidebar(devices: List<AdbDevice>, model: String?, jobs: List<TransferJobSnapshot>, onDevice: (AdbDevice) -> Unit, onLocation: (RemotePath) -> Unit, onTransferAction: (String, String) -> Unit) {
    Surface(color = Color(0xFFF7F7F7), modifier = Modifier.width(230.dp).fillMaxHeight()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SidebarItem("⌂", "Home", false) { onLocation(RemotePath.of("/sdcard")) }; SidebarItem("↓", "Downloads", false) { onLocation(RemotePath.of("/sdcard/Download")) }; Text("Devices", color = Color(0xFF666666), modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)); devices.forEach { SidebarItem("▣", it.attributes["model"] ?: it.serial, model == it.attributes["model"]) { onDevice(it) } }; if (jobs.isNotEmpty()) {
            Text("Transfers", color = Color(0xFF666666), modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)); jobs.takeLast(3).forEach { job ->
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Text("${job.state} · ${formatSize(job.bytesDone)} / ${formatSize(job.totalBytes)}"); LinearProgressIndicator(progress = { if (job.totalBytes > 0) job.bytesDone.toFloat() / job.totalBytes else 0f }, Modifier.fillMaxWidth()); Row {
                    when (job.state) {
                        TransferState.RUNNING -> TextButton({ onTransferAction(job.id, "pause") }) { Text("Pause") }; TransferState.PAUSED -> TextButton({ onTransferAction(job.id, "resume") }) { Text("Resume") }; TransferState.FAILED, TransferState.CANCELLED -> TextButton({ onTransferAction(job.id, "retry") }) { Text("Retry") }; else -> Unit
                    }
                }
                }
            }
        }
        }
    }
}

@Composable
private fun SidebarItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (selected) Color(0xFFE1EAF4) else Color.Transparent, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)) { Row(Modifier.height(36.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(icon, Modifier.width(26.dp)); Text(label) } }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "${bytes / 1024} KB"; bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"; else -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
}

internal fun fileUrisToPaths(values: List<String>): List<Path> = values.mapNotNull { value -> runCatching { URI(value) }.getOrNull()?.takeIf { it.scheme.equals("file", ignoreCase = true) }?.let { runCatching { Path.of(it) }.getOrNull() } }

@Composable
internal fun RemoteSearchDialog(query: String, results: List<RemoteEntry>, running: Boolean, limitReached: Boolean, onQueryChange: (String) -> Unit, onSearch: (String) -> Unit, onOpen: (RemoteEntry) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search current folder") },
        text = {
            Column(Modifier.width(560.dp).heightIn(min = 240.dp, max = 480.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, onQueryChange, Modifier.weight(1f).testTag("search-query"), singleLine = true, label = { Text("File or folder name") })
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSearch(query) }, enabled = query.isNotBlank() && !running, modifier = Modifier.testTag("search-start")) { Text("Search") }
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (limitReached) Text("Result or directory limit reached; refine the query.", color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) { items(results, key = { it.path.value }) { entry -> TextButton(onClick = { onOpen(entry) }, modifier = Modifier.fillMaxWidth().testTag("search-result-${entry.path.value}")) { Text(entry.path.value, Modifier.fillMaxWidth()) } } }
                if (!running && results.isEmpty()) Text("No results", Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("search-close")) { Text(if (running) "Cancel" else "Close") } },
    )
}

@Composable
internal fun ManagedEditSyncDialog(session: ManagedEditSession, syncing: Boolean, onSync: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit and sync") },
        text = { Column { Text("The file is open in its Windows editor."); Text(session.remotePath.value); Text("After saving in the editor, upload only if the remote version has not changed.", Modifier.padding(top = 8.dp)); if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) } },
        confirmButton = { Button(onClick = onSync, enabled = !syncing, modifier = Modifier.testTag("edit-sync")) { Text("Upload changes") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !syncing) { Text("Keep remote unchanged") } })
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun fileListClipEntry(paths: List<Path>): ClipEntry {
    val files = paths.map { it.toFile() }; return ClipEntry(object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor);
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor;
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor); return files
        }
    })
}
