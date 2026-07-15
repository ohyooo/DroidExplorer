# DroidFiles

DroidFiles 是一个面向 Windows 11 的 Android 文件管理器。桌面端使用 Compose Desktop，Android 端通过 ADB 临时启动 `app_process` Server；握手完成后的浏览、文件操作和传输均走自定义协议。

## 环境要求

- JDK 21 或更高版本
- Android SDK 37 和 platform-tools
- Windows 11 x64
- 带 C++ 工具链的 Visual Studio Build Tools（用于 OLE 虚拟文件拖放）

## 构建与运行

```powershell
.\gradlew.bat build
.\gradlew.bat :server-android:serverJar
.\gradlew.bat :cli:run --args="devices"
.\gradlew.bat :desktop-app:run
.\gradlew.bat packageRelease
.\integration-tests\android-e2e.ps1 -Serial emulator-5554
.\integration-tests\benchmark-transfer.ps1 -Serial emulator-5554 -SizeMiB 16
```

两个设备之间可直接中继单个文件，PC 端只使用固定大小缓冲区，不创建完整临时文件：

```powershell
.\cli\build\install\cli\bin\cli.bat relay <源序列号> <目标序列号> server-android\build\server\droidfiles-server.jar /源路径 /目标路径
```

在 IntelliJ IDEA 中可直接选择共享运行配置 `DroidFiles Desktop`。它执行 `:desktop-app:run`；连接设备仍由应用内设备列表完成。

连接后可使用多标签浏览，标签路径和活动标签会写入 `%LOCALAPPDATA%/DroidFiles/settings.json` 并在下次握手后恢复。支持 `Ctrl+T/W/Shift+T/A/C/X/V`、`Alt+←/→/↑`、`F2`、`F5`、`Enter` 和 `Delete`。设备连接中断时当前标签设置会保留，错误栏提供“Reconnect”入口。

工具栏 Search（或 `Ctrl+F`）执行可取消递归搜索，最多返回 500 项、最多扫描 10,000 个目录且不跟随符号链接。活动标签每 5 秒做一次低优先级目录校验，变化后保留仍存在的选择项并刷新。

文件上下文菜单提供“Edit and sync”：先打开受管缓存，用户明确点击上传后再次校验远端 size/mtime；远端已变化时拒绝覆盖，否则上传随机临时文件并原子替换。“Copy file to clipboard”先完成受管缓存，再通过 Compose `LocalClipboard/ClipEntry` 提供标准文件列表供 Explorer 粘贴。

“Settings”中可配置 Root/设备权限和扩展名打开方式。自定义打开方式使用可执行文件与逐行参数数组（`{file}` 为本地缓存文件），不会拼接 `cmd.exe` 命令；删除扩展名规则即可恢复 Windows 系统默认程序。

基准脚本会校验 ADB 与 DroidFiles 传输结果的 SHA-256，并把设备、ADB 版本、连接类型和吞吐量写入 `integration-tests/benchmark-last.json`。

ADB 只用于设备发现、推送 Server、启动 `app_process` 和建立端口转发。建立会话后不会为每次文件操作调用 `adb shell`、`adb push` 或 `adb pull`。默认权限为 Android `shell` UID，无法绕过 Android 权限或分区限制。

本地文件拖入使用 Compose Multiplatform 官方 `dragAndDropTarget`/`DragData.FilesList` API，业务层只接收 Kotlin `Path`。远程文件拖出 Windows Explorer 需要 Shell 的虚拟文件流格式，Compose 公共 API不能表达 `FILEDESCRIPTOR/FILECONTENTS/IStream`，因此仅这一段通过 `platform-windows` 的 Kotlin 接口调用隔离的 JNI/OLE 实现；应用内状态和手势仍由 Compose 管理。

CLI 文件命令格式为 `<serial> <server-jar> <command>`，例如：

```powershell
.\gradlew.bat :cli:run --args="emulator-5554 server-android/build/server/droidfiles-server.jar ls /sdcard"
```

UI 会先显示协议返回的目录项，再为当前可见行异步读取 Windows Shell 文件关联图标。可见图片在 200 ms 空闲窗口后进入最多 2 路的低优先级预览队列；单项上限 4 MiB，按路径、大小和修改时间缓存。滚出视口、导航或断线会取消旧任务，不影响目录首屏。

更多信息参见[协议说明](docs/protocol.md)、[架构说明](docs/architecture.md)、[UI 加载与预览](docs/ui-rendering.md)和[实现进度](docs/PROGRESS.md)。
