# Windows Shell acceptance

Run on Windows 11 Explorer after `gradlew packageRelease`. Verify inbound and outbound single/multiple files, Unicode names, empty files/directories, nested directories and a sparse file over 4 GiB. Confirm extraction begins only when Explorer requests FILECONTENTS, cancellation stops streams, the advertised effect is COPY, and remote sources remain. Remove the native DLL and confirm the UI explicitly identifies fallback preparation rather than
claiming virtual streaming. Verify default-program and custom association arguments with spaces and Chinese characters.

当前状态：`FILEDESCRIPTOR/IDataObject/IStream` 测试覆盖 Unicode、空文件/目录、嵌套目录、超过 4 GiB 的元数据、异步能力状态、认证 loopback range、无效 token 和取消清理。Native 测试还把虚拟数据对象直接投递给 Windows Shell 文件夹的真实 `IDropTarget`，由 Explorer 使用的 Shell 处理程序解包到隔离临时目录，并校验嵌套 Unicode 内容和空文件；测试结束清理目录。遍历安全的缓存降级准备器覆盖进度、取消和嵌套树，DLL 不可用时会打开缓存目录并显示明确提示。

入站不再安装 AWT `DropTarget` 适配器；桌面根节点使用 Compose Multiplatform 官方 `dragAndDropTarget`，只接受 `DragData.FilesList`，file URI 到 Kotlin `Path` 的 Unicode/空格转换有回归测试。Windows OLE 仅保留给 Compose API无法表达的远程虚拟文件按需拖出。

可见 Explorer 窗口中的人工鼠标拖放仍保留为发布前体验检查，但不再是核心数据对象正确性的唯一证据；自动测试已经执行同一个 Windows Shell 文件夹 DropTarget 提取路径。
