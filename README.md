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
- 支持多种数据格式化类型（如 JSON、XML、Base64 等）
- 输入数据、格式化结果分区显示
- 支持清空日志

### 5. 字符串工具
- **文件**：`strData-format-view.fxml`
- 支持字符串常用操作（如大小写转换、去重、分割等）
- 输入数据、格式化结果分区显示
- 支持清空日志

### 6. 启动项工具
- **文件**：`app-launcher-view.fxml`
- 可批量管理常用程序路径
- 支持启动选中、启动全部、结束进程、移除、清除全部
- 日志支持一键清空

### 7. 主界面
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

---

## 运行要求

- JDK 23 及以上（推荐 Java 23+）
- JavaFX 16 及以上
- 相关依赖库（如 org.json、Gson 等）

---

## 运行步骤
1.  点击maven->插件->javafx->favafx:jlink
2.  通过项目中run.bat运行，运行时候注意配置jdk路径和项目打包后的路径

## 贡献/反馈

如有建议、bug或需求，欢迎提交 issue 或 PR ～

---