# Implementation progress

Last updated: 2026-07-13

- [x] Repository and environment inspected.
- [x] Version-locked Gradle multi-module skeleton initialized.
- [x] Protocol framing/path validation and core public interfaces baseline.
- [x] Android server artifact and ADB bootstrap baseline; real emulator stat round-trip verified.
- [x] CLI device discovery and localhost fake-server baseline.
- [x] Authenticated filesystem/transfer implementation, resumable partial files and cancellation-safe data sockets.
- [x] RAW_FILE upload/download, offset resume, atomic partial files and bounded transfer manager baseline.
- [x] CLI devices/ls/stat/mkdir/pull/push/mv/cp/rm/hash verified on emulator.
- [x] Desktop embeds Server JAR, discovers/connects devices and streams directory listings.
- [x] Open-file cache and atomic settings persistence with tests.
- [x] TREE_STREAM single-connection directory upload/download with safe paths, empty directories/files and Unicode verified on emulator.
- [x] Explorer-grade interaction acceptance；Compose 官方 API 负责本地文件拖入，Windows Shell 真实 `IDropTarget` 自动提取覆盖虚拟文件拖出。
- [x] Full Gradle build and current automated tests pass.
- [x] Authenticated hello, major/minor negotiation and capability response verified on emulator.
- [x] MSVC/CMake/Ninja native build discovered; FILEDESCRIPTOR Unicode, directory, empty and >4 GiB tests pass.
- [x] Native IDataObject/IDataObjectAsyncCapability/IDropSource core, FILECONTENTS lindex validation, directory rejection, IStream ownership and async lifecycle tests pass.
- [x] Token-authenticated loopback content service, native random-access NetworkStream, typed JNI manifest and COPY-only DoDragDrop；Windows Shell 文件夹真实 `IDropTarget` 自动提取通过。
- [x] Explorer 拖入使用 Compose Multiplatform `dragAndDropTarget`/`DragData.FilesList`，COPY 上传通过 TransferManager。
- [x] ShellExecuteExW default open and safe ProcessBuilder custom-program boundary compile and test.
- [x] Double-click remote file uses fingerprinted managed cache, cancellable RAW_FILE download, atomic `.part` commit, session lease and default/custom program dispatch.
- [x] Background recursive drag manifest with cancellation keys, Unicode/nested/empty directory tests, and row drag gesture invokes native virtual drag.
- [x] Transfer center reports total bytes, smoothed speed and ETA and exposes pause/resume/cancel/retry controls.
- [x] 递归搜索、活动目录低频刷新、可见范围图片预览、编辑冲突检查与原子同步、Compose 剪贴板及跨设备中继完成。
- [x] 最终全量构建、65 项 JVM/Compose 测试、Android 模拟器 E2E 和 Windows Shell 原生测试全部通过。

Verified 2026-07-11: `gradlew build` succeeded; emulator-5554 accepted the app_process server, dynamic forward, framed STAT `/sdcard`, returned a valid response, unlinked its JAR, and the forward was removed.

Verified 2026-07-12: authenticated ClientHello/ServerHello followed by STAT `/sdcard` succeeded on emulator-5554. Native descriptor tests built with VS 2026 MSVC via CMake 4.1.2/Ninja.

Verified 2026-07-12: CLI Android E2E performed mkdir, 1 MiB push/pull with matching SHA-256, remote copy, move, list and recursive delete. `gradlew build` completed 121 tasks, `packageRelease` produced a portable runtime, and its executable remained running until an intentional shutdown.

## 2026-07-13 UI 交互与系统图标

- 目录行的单击、双击改为统一手势处理；真实桌面鼠标双击已选中目录的回归测试通过。
- 文件和目录图标由 Windows Shell 按当前用户文件关联提供，并按扩展名/目录和尺寸缓存。
- 目录元数据和文字先显示，系统图标只在 LazyColumn 当前可见行中异步加载。
- 地址栏路径裁切已修复，后退/前进按钮使用活动标签的真实历史状态。
- IntelliJ IDEA 可使用共享运行配置 `DroidFiles Desktop`。
- 自动内容预览已完成：目录文字和系统图标优先，仅为可见行延迟请求，并发、大小和缓存均有界。
- Desktop 12 项测试、Windows 平台 6 项测试和全量 `build packageRelease`（139 个任务）通过。

Verified 2026-07-12: TREE_STREAM round-tripped a Unicode nested tree containing an empty directory, empty file and binary file through one data connection; local SHA-256 manifests matched.

Environment: Windows, JDK 25 installed, Android SDK at `D:/Documents/Android`; Gradle, CMake and MSVC are not installed globally. Versions and Gradle 9.6.1 wrapper follow the adjacent, working Calendar project. Native verification requires Visual Studio Build Tools.
