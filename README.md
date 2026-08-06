# JavaFX Tools 多功能工具箱

本项目是一个基于 JavaFX 的多功能工具箱，集成了 HTTP 请求调度、WebSocket 客户端、网络工具、数据/字符串格式化、应用启动项管理等常用开发工具。支持一键启动、清空日志、模板管理等便捷功能。界面采用多 Tab 设计，模块划分清晰，便于日常开发和调试使用。

---

## 主要功能模块

### 1. HTTP 请求调度器
- **文件**：`http-request-view.fxml`
- 支持 GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS 多种请求方式
- 支持自定义请求头、请求参数、超时时间
- 支持批量定时请求、请求模板的保存/载入/删除
- 响应结果可美化显示（如 JSON 格式化）
- 日志支持一键清空

### 2. WebSocket 客户端
- **文件**：`websocket-view.fxml`
- 支持输入服务器地址并连接/断开
- 支持发送消息与消息记录显示
- 支持消息记录一键清除

### 3. 网络工具
- **文件**：`network-tools-view.fxml`
- 支持主机名/IP 查询
- 查询日志可一键清空

### 4. 数据格式化
- **文件**：`data-format-view.fxml`
- 支持 JSON、XML 格式化
- 输入数据、格式化结果分区显示
- 支持清空日志

### 5. 字符串工具
- **文件**：`strData-format-view.fxml`
- 支持去除空白字符、大小写转换
- 输入数据、格式化结果分区显示
- 支持清空日志

### 6. 启动项工具
- **文件**：`app-launcher-view.fxml`
- 可批量管理常用程序路径
- 支持启动选中、启动全部、结束进程、移除、清除全部
- 日志支持一键清空

### 7. 备忘提醒
- **文件**：`memo-reminder-view.fxml`
- 支持按固定间隔重复提醒，也可指定日期和具体时间创建一次性闹钟
- 到时主动恢复主窗口并置顶弹框提醒，可勾选“已处理”完成提醒或进入下个周期
- 未处理可一键稍后 5 分钟再次提醒
- 提醒任务支持暂停/恢复/删除，并自动持久化到系统用户数据目录

### 8. JAR 应用启动器
- **文件**：`jar-launcher-view.fxml`
- 支持维护 JAR 项目、复制构建产物、端口查询以及启动/停止应用

### 9. 域名保活
- **文件**：`keepalive-manager-view.fxml`
- 支持 HTTP/Ping 探测、随机访问间隔和多域名独立配置

### 10. 主界面
- **文件**：`main-view.fxml`
- 采用 TabPane 管理各个功能模块
- 提供中央系统日志区

---

## 常用操作说明

- **日志清空**：各功能区日志都支持一键清空，方便查看最新结果。
- **模板管理**：HTTP请求支持保存、载入、删除请求模板，便于复用常用配置。
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
| data-format-view.fxml  | 数据格式化          |
| strData-format-view.fxml| 字符串工具         |
| app-launcher-view.fxml | 启动项工具          |
| memo-reminder-view.fxml| 备忘提醒            |
| jar-launcher-view.fxml | JAR应用启动器       |
| keepalive-manager-view.fxml | 域名保活管理  |

---

## 运行要求

- JDK 23
- JavaFX 23.0.1
- Maven 3.9+
- Jackson、Gson、Java-WebSocket 等依赖由 Maven 自动管理

运行数据统一保存在 `%LOCALAPPDATA%\FxTools`；非 Windows 系统保存在用户目录下的 `.fxtools`。

---

## 运行步骤
1. 执行 `mvn clean test` 运行测试。
2. 运行仓库根目录 `build-exe.bat` 构建 Windows 应用。
3. 脚本会先执行 `mvn clean javafx:jlink` 生成 jlink 运行时，再执行 `jpackage` 输出 Windows 应用包。
4. 如需调整应用名、版本、主模块、主类或输出目录，可在执行前设置 `APP_NAME`、`APP_VERSION`、`MAIN_MODULE`、`MAIN_CLASS`、`OUTPUT_DIR` 等环境变量。
5. 执行前请确保 Maven 可用，并且 `JAVA_HOME` 指向包含 `jpackage.exe` 的完整 JDK，或已将 `jpackage.exe` 加入 `PATH`。

## 性能优化说明
- 中央日志区域增加了行数上限与批量裁剪策略，避免长时间运行导致内存持续增长。
- 日志区域引用改为弱引用，减少界面关闭后对象无法释放的风险。
- 启动项状态轮询每轮只读取一次系统进程快照，状态未变化时不刷新列表。
- 批量启动采用短间隔顺序调度，移除了每个应用独占后台线程数秒的重复监控。
- WebSocket 消息和域名保活日志采用有界批量刷新，空闲时不会持续唤醒日志线程。

## 贡献/反馈

如有建议、bug或需求，欢迎提交 issue 或 PR ～

---
