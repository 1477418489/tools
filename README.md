# FxTools

FxTools 是面向 Windows 的本地开发与运维工具箱，使用 C#、.NET 10、WinUI 3 和 Windows App SDK 开发。应用不依赖 JRE，配置继续保存在 `%LOCALAPPDATA%\FxTools`，并兼容 JavaFX 版本生成的 JSON 数据。

## 功能

| 分组 | 模块 | 主要能力 |
|---|---|---|
| 启动与运行 | 应用启动项 | 路径管理、批量启动、安全停止、运行状态与启动间隔 |
| 启动与运行 | JAR 启动 | 项目配置、部署文件复制、端口归属、启停、目录与日志 |
| 启动与运行 | Windows 电源 | 关机、重启、休眠、定时唤醒和只读固件诊断 |
| 网络与接口 | HTTP 请求 | 多方法、请求头/正文、模板、超时、定时发送和响应保存 |
| 网络与接口 | WebSocket | 连接、收发、断开和有界消息历史 |
| 网络与接口 | 网络诊断 | DNS、IP、公网出口和归属信息 |
| 网络与接口 | 网络质量 | HTTP、TCP、TLS、STUN、HTTP 代理、趋势和统计 |
| 网络与接口 | 域名保活 | HTTP/Ping、多目标、随机间隔和独立状态 |
| 系统与开发 | 进程与端口 | Windows IP Helper 快照、筛选、详情复制和安全终止 |
| 系统与开发 | 环境体检 | Java、Maven、Git、Node、npm、Python 和 Docker |
| 系统与开发 | 文件分析 | 流式哈希、编码/类型、元数据、占用探测和取消 |
| 系统与开发 | 日志监控 | tail、多规则匹配、多选触发、远程判断和窗口自动输入 |
| 数据与效率 | 数据格式化 | JSON/XML 格式化 |
| 数据与效率 | 字符串处理 | 空白移除和大小写转换 |
| 数据与效率 | Base64 | 标准、URL、MIME 变体和多字符编码 |
| 数据与效率 | 备忘提醒 | 周期/定时、暂停、恢复、完成、稍后 5 分钟和声音 |
| 设置 | 桌面集成 | 托盘、关闭行为、开机启动、数据目录和 ZIP 备份 |

## 日志自动响应安全

- 自动响应规则可多选，例如同时选择 `429`、`503`。
- 目标窗口必须由 PID 与完整标题共同唯一确定。
- 发送前重新检查 PID、标题和前台窗口；任一条件变化都会拒绝输入。
- 不会退回到同标题的其他进程，也不会在前台激活失败时发送按键。
- Windows 禁止低权限进程向管理员进程注入输入。目标程序已提升时，FxTools 也需要以管理员身份运行。

## 开发环境

- Windows 10 19041 或更高版本，推荐 Windows 11 x64
- .NET SDK 10.0.400 或兼容的 10.0 补丁版本
- Windows App SDK 1.8
- Windows SDK 10.0.26100
- Visual Studio 2022/2026 的 WinUI 应用开发组件仅在需要设计器和 IDE 调试时使用；命令行构建不要求打开 Visual Studio

## 构建和运行

```powershell
dotnet restore FxTools.slnx
dotnet test tests\FxTools.Core.Tests\FxTools.Core.Tests.csproj -c Debug
dotnet build FxTools.slnx -c Debug
dotnet run --project src\FxTools.App\FxTools.App.csproj
```

生成可在未安装 .NET/Windows App SDK 运行时的 x64 机器上运行的自包含目录：

```powershell
.\build-exe.bat
```

入口位于 `dist\FxTools\FxTools.exe`，同目录会生成 SHA-256 文件。WinUI 3 不适合强制压成单文件，发布目录中的 DLL 和运行时文件必须与 EXE 一起分发。

## 数据兼容

数据目录为 `%LOCALAPPDATA%\FxTools`。主要文件：

| 文件 | 内容 |
|---|---|
| `settings.json` | 托盘、提醒声音和开机启动 |
| `app_launcher_paths.json` | 应用启动项 |
| `app_launcher_settings.json` | 批量启动间隔 |
| `jar_launcher_projects.json` | JAR 项目 |
| `http_templates.json` | HTTP 请求模板 |
| `network_quality_targets.json` | 网络质量目标 |
| `network_quality_settings.json` | 网络质量和代理设置 |
| `keepAlive.json` | 域名保活 |
| `log-monitor.json` | 日志规则和自动响应 |
| `memo_reminders.json` | 备忘提醒 |

JSON 使用 UTF-8 原子写入。现有文件格式无效时应用会报告错误并拒绝覆盖，设置页可将整个数据目录导出为 ZIP 快照。

## 资源边界

- UI 日志最多 800 行、1,000,000 字符。
- 日志监控每轮最多读取 256 KiB/200 行，单行最多 32 KiB。
- 自动响应只有一个消费者，队列最多 32 项。
- HTTP 响应正文最多保留 200,000 字符。
- Base64 输入最多 1,000,000 字符。
- JAR 日志达到 50 MiB 时轮转。
- 提醒使用一个应用级调度循环，不为每条提醒创建线程。

需求、架构、迁移记录和功能验证证据见 [docs/winui](docs/winui/)。
