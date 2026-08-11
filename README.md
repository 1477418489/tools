# JavaFX Tools 多功能工具箱

本项目是一个基于 JavaFX 的 Windows 本地开发工具箱，集成进程与端口查看、开发环境体检、网络诊断、网络质量监控、文件分析、Base64 编解码、HTTP/WebSocket 调试、应用与 JAR 管理、备忘提醒、域名保活和电源计划。界面采用浅色分组工作区与按需加载机制，长期驻留时减少无效轮询和后台占用。

---

## 主要功能模块

### 1. HTTP 请求调度器
- **文件**：`http-request-view.fxml`
- 支持 GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS 多种请求方式
- 支持自定义请求头、请求参数、超时时间
- 支持立即发送单次请求、定时重复请求以及请求模板的保存/载入/删除
- 最新响应在页面内独立显示状态码、耗时、正文和响应头
- 响应正文支持 JSON 格式化、复制、保存和独立清空

### 2. WebSocket 客户端
- **文件**：`websocket-view.fxml`
- 支持输入服务器地址并连接/断开
- 支持发送消息与消息记录显示
- 支持消息记录一键清除

### 3. 网络工具与网络质量
- **文件**：`network-tools-view.fxml`、`network-quality-view.fxml`
- 支持主机名/IP 查询，查询日志可一键清空
- 网络质量页直接监控 HTTP/HTTPS 服务地址，记录状态码、RTT、失败率、连续失败和趋势
- TCP、TLS、STUN 作为高级探测协议，并可按系统路由或显式 HTTP CONNECT/SOCKS5 代理监控

### 4. 进程与端口中心
- **文件**：`process-port-view.fxml`
- 按需读取 Windows 进程和 TCP 监听端口快照，不执行后台轮询
- 支持按进程名、PID、用户、命令行和端口联合筛选
- 展示工作集、累计 CPU 时间、监听端口和命令行，并支持复制所选详情
- 终止进程前二次确认，禁止终止系统关键进程和 FxTools 自身
- 系统权限不足时降级使用 Java `ProcessHandle`，并明确标记部分数据受限

### 5. 开发环境体检
- **文件**：`dev-environment-view.fxml`
- 并行检查 Java/JDK、Maven、Git、Node.js、npm、Python 和 Docker CLI
- 展示实际版本与可执行文件路径，检测 `JAVA_HOME` 与 `javac` 路径不一致
- 每项命令均有超时和输出上限，体检仅在进入页面或手动刷新时执行
- 支持一键复制完整体检报告

### 6. 文件分析
- **文件**：`file-analysis-view.fxml`
- 流式计算 SHA-256、SHA-1 和 MD5，不将整个文件载入内存
- 识别 BOM、UTF-8、UTF-16、GB18030/GBK 文本特征和二进制内容
- 展示文件类型、大小、修改时间、读写权限和临时独占锁探测结果
- 大文件分析支持取消；SHA-1 和 MD5 仅作为文件比对摘要

### 7. 数据格式化
- **文件**：`data-format-view.fxml`
- 支持 JSON、XML 格式化
- 输入数据、格式化结果分区显示
- 支持清空日志

### 8. 字符串工具
- **文件**：`strData-format-view.fxml`
- 支持去除空白字符、大小写转换
- 输入数据、格式化结果分区显示
- 支持清空日志

### 9. Base64 编解码
- **文件**：`base64-view.fxml`
- 支持标准、URL 安全和 MIME Base64 变体
- 支持 UTF-8、GB18030/GBK、UTF-16 LE/BE 字符编码
- 支持结果复制、结果转输入、剪贴板粘贴和输入大小限制
- Base64 是可逆编码而非加密，不用于保护敏感信息

### 10. 启动项工具
- **文件**：`app-launcher-view.fxml`
- 可批量管理常用程序路径
- 支持启动选中、启动全部、结束进程、移除、清除全部
- 列表直接显示每个应用的运行状态；页面或窗口不可见时暂停自动状态轮询

### 11. 备忘提醒
- **文件**：`memo-reminder-view.fxml`
- 支持按固定间隔重复提醒，也可指定日期和具体时间创建一次性闹钟
- 到时主动恢复主窗口并置顶弹框提醒，可勾选“已处理”完成提醒或进入下个周期
- 未处理可一键稍后 5 分钟再次提醒
- 提醒任务支持暂停/恢复/删除，并自动持久化到系统用户数据目录
- 可在设置中关闭提醒声音，关闭主窗口后仍可驻留托盘等待提醒

### 12. JAR 应用启动器
- **文件**：`jar-launcher-view.fxml`
- 支持维护 JAR 项目、复制构建产物、端口查询以及启动/停止应用
- 项目列表持续显示运行、停止、检查中、端口冲突和异常状态
- 启停操作会校验端口进程是否属于目标 JAR，不会终止无关监听进程
- Java 进程输出写入目标 JAR 目录下的 `jar-launcher-<端口>.log`
- 可从项目操作区直接打开目标目录和当前端口日志

### 13. 域名保活
- **文件**：`keepalive-manager-view.fxml`
- 支持 HTTP/Ping 探测、随机访问间隔和多域名独立配置

### 14. Windows 电源计划
- **文件**：`windows-power-view.fxml`
- 支持创建一次性定时关机、重启和休眠任务，关机或重启触发后保留 60 秒系统倒计时
- 支持创建和取消 Windows 定时唤醒任务，并读取任务计划程序中的现有状态
- 电源计划由 Windows 任务计划程序持久化，应用退出后仍然有效
- 定时唤醒用于从睡眠或休眠恢复，依赖设备固件和系统唤醒计时器，不保证完全关机或断电后的冷启动
- BIOS / 电源检测页只读展示品牌型号、序列号、UUID、主板、CPU、内存、Windows、BIOS 发布日期、UEFI、睡眠与唤醒能力
- 来电自启与 RTC 冷启动明确标记为需要在固件中确认，不读取或修改 BIOS 设置变量

### 15. 主界面
- **文件**：`main-view.fxml`
- 采用 TabPane 管理各个功能模块
- 备忘提醒和域名保活随应用启动，其余模块首次进入时加载
- 普通执行日志通过底部紧凑入口打开独立窗口，WebSocket 消息流保留内嵌显示
- 设置菜单提供关闭到托盘、提醒声音、Windows 登录启动、打开数据目录和 ZIP 数据备份

---

## 常用操作说明

- **日志查看**：点击页面底部日志入口打开完整窗口，可搜索、复制、保存、清空并调整字号和换行。
- **模板管理**：HTTP请求支持保存、载入、删除请求模板，便于复用常用配置。
- **数据备份**：通过“设置 -> 导出数据备份”将当前全部配置导出为 ZIP 文件。
- **批量/定时操作**：HTTP请求/应用启动项等支持批量执行与定时调度。
- **数据格式化**：支持多类型数据和字符串格式化，适合开发调试场景。

---

## FXML 文件结构说明

| FXML文件               | 作用/模块名         |
|------------------------|---------------------|
| main-view.fxml         | 主界面Tab管理       |
| http-request-view.fxml | HTTP请求调度器      |
| websocket-view.fxml    | WebSocket客户端     |
| network-tools-view.fxml| 网络工具            |
| process-port-view.fxml | 进程与端口中心      |
| dev-environment-view.fxml | 开发环境体检     |
| file-analysis-view.fxml | 文件分析           |
| data-format-view.fxml  | 数据格式化          |
| strData-format-view.fxml| 字符串工具         |
| base64-view.fxml       | Base64 编解码       |
| app-launcher-view.fxml | 启动项工具          |
| memo-reminder-view.fxml| 备忘提醒            |
| jar-launcher-view.fxml | JAR应用启动器       |
| keepalive-manager-view.fxml | 域名保活管理  |
| windows-power-view.fxml | Windows电源计划    |

---

## 运行要求

- JDK 23
- JavaFX 23.0.1
- Maven 3.9+
- Jackson、Gson、Java-WebSocket 等依赖由 Maven 自动管理

运行数据统一保存在 `%LOCALAPPDATA%\FxTools`；非 Windows 系统保存在用户目录下的 `.fxtools`。主界面“设置 -> 打开数据目录”可直接访问该位置。

当前格式的数据文件包括：

| 文件 | 内容 |
|------|------|
| `settings.json` | 托盘、提醒声音和开机启动设置 |
| `app_launcher_paths.json` | 应用启动项 |
| `app_launcher_settings.json` | 启动项批量启动间隔 |
| `network_quality_targets.json` | 网络质量监控目标 |
| `network_quality_settings.json` | 网络质量出口、代理和探测参数（不含密码） |
| `http_templates.json` | HTTP 请求模板 |
| `jar_launcher_projects.json` | JAR 项目 |
| `memo_reminders.json` | 备忘提醒 |
| `keepAlive.json` | 域名保活配置 |

---

## 运行步骤
1. 执行 `mvn clean test` 运行测试。
2. 运行仓库根目录 `build-exe.bat` 构建 Windows 应用。
3. 脚本会先执行 `mvn clean javafx:jlink` 生成 jlink 运行时，再执行 `jpackage` 输出 Windows 应用包。
4. 如需调整应用名、版本、主模块、主类或输出目录，可在执行前设置 `APP_NAME`、`APP_VERSION`、`MAIN_MODULE`、`MAIN_CLASS`、`OUTPUT_DIR` 等环境变量。
5. 执行前请确保 Maven 可用，并且 `JAVA_HOME` 指向包含 `jpackage.exe` 的完整 JDK，或已将 `jpackage.exe` 加入 `PATH`。

## 性能优化说明
- 所有日志缓冲区均有行数、字符数和积压数量上限，批量刷新以避免高频 UI 更新。
- 普通日志默认仅显示紧凑入口，完整内容按需打开，不长期占用主工作区。
- 非常驻功能页首次访问时才创建；退出时只清理已经加载的模块。
- 启动项状态轮询每轮只读取一次系统进程快照，窗口隐藏、最小化或切换页面后暂停轮询。
- 批量启动按列表顺序执行，并使用用户配置的相邻启动间隔处理前后依赖关系。
- WebSocket 消息和域名保活日志采用有界批量刷新，空闲时不会持续唤醒日志线程。
- 电源计划只在页面加载或用户操作时访问 Windows 任务计划程序，不增加常驻轮询任务。
- 进程、端口和开发环境均采用用户触发的有界快照，不新增常驻系统轮询。
- 文件哈希采用固定缓冲区流式处理，Base64 输入设有字符上限，耗时操作均在后台执行并可在模块关闭时取消。

## 贡献/反馈

如有建议、bug或需求，欢迎提交 issue 或 PR ～

---
