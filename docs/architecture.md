# 架构说明

- `protocol`：帧协议、版本/错误模型和安全远程路径；不依赖 UI。
- `client-core`：会话、远程文件系统、传输接口、并发控制连接和缓存。
- `transport-adb`：ADB 定位、设备状态、参数转义、推送、转发及 `app_process` 生命周期。
- `server-android`：构建为未签名 APK 后重命名为 JAR，以 shell/root 身份运行。
- `cli`：不依赖 Compose 的协议参考客户端。
- `testkit`：本机 Fake Server 和故障注入夹具。
- `desktop-app`：Compose Desktop 界面、状态管理和可见范围调度。
- `platform-windows`：Windows Shell、系统关联图标、拖放和 JNI 隔离边界。
- `windows-shell-bridge`：OLE 虚拟文件数据对象和延迟内容流原生实现。

Android Server 只对首次控制 socket 的 `accept()` 设置 30 秒启动超时；握手成功后的控制会话不设固定寿命。控制连接结束时关闭 `LocalServerSocket`，从而解除数据连接接收循环，并由 ADB bootstrap 清理 forward 和临时 JAR。

长任务使用子 `SupervisorJob`、结构化协程和 IO Dispatcher。控制连接由有界 Channel 串行写入，一个读取器按 requestId 分发响应。协议和核心模块不出现 Compose/AWT 类型。

目录流到达后立即更新 `ExplorerViewModel`。系统图标和后续缩略图只由 `LazyColumn` 已组合的可见行请求；图标通过 JDK 官方 `FileSystemView` 进入 Windows Shell，按当前用户的注册表文件关联解析，并按扩展名/目录和尺寸缓存。

Explorer/桌面向应用拖入本地文件由 Compose Multiplatform 的公共 `dragAndDropTarget` 接收，并把 `DragData.FilesList` 的 file URI 安全转换为 Kotlin `Path`。远程文件拖出是平台能力：公共 Kotlin 接口描述 manifest 和流读取，Windows 实现才使用 OLE `IDataObject/IStream`；其他平台可以提供各自实现，不让 COM 类型进入 ViewModel、传输或协议模块。

搜索采用 client-core 的广度优先队列，结果数和扫描目录数均有硬上限，每个目录批次检查协程取消。Server 尚未协商目录事件能力时，活动 ViewModel 使用 5 秒低频列表校验作为可靠降级；导航请求优先，轮询不会与正在加载的标签竞争。

编辑同步记录开始编辑时的远端指纹。提交时重新 `stat`，冲突则拒绝覆盖；无冲突时 RAW 上传到同目录随机临时名，再用协议 rename 原子替换。跨设备中继同时持有源下载和目标上传数据连接，只分配不超过 1 MiB 的缓冲区并继承结构化取消。
