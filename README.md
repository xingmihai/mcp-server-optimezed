<div align="center">

# MCP 服务器

[![License: GPL v3](https://img.shields.io/badge/License-GPL_v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![Min SDK](https://img.shields.io/badge/minSdk-26-orange)](#)
[![Target SDK](https://img.shields.io/badge/targetSdk-35-orange)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-2024.06-4285F4?logo=jetpackcompose&logoColor=white)](#)
[![MCP](https://img.shields.io/badge/MCP-Streamable_HTTP-blue)](https://modelcontextprotocol.io/)

</div>

运行在 Android 手机上的本地 [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) 服务器。通过 Streamable HTTP 协议向 Claude Desktop、Cursor 等 MCP 客户端暴露手机能力：文件操作、系统管理、设备信息、应用管理、脚本执行、通讯交互、网络请求、实用工具等。

> [!IMPORTANT]
> 本项目实现的是 MCP **服务端**，运行在被控的 Android 设备上；MCP 客户端运行在 PC 或另一台设备，通过局域网或内网穿透与之通信。

---

## 目录

- [核心特性](#核心特性)
- [工作原理](#工作原理)
- [工具清单](#工具清单)
- [客户端接入](#客户端接入)
- [权限说明](#权限说明)
- [致谢](#致谢)
- [许可证](#许可证)

---

## 核心特性

- **标准 MCP 协议**：实现 Streamable HTTP 传输（`POST /mcp`，JSON-RPC 2.0），同时兼容 SSE（`GET /sse`）。
- **可选 Bearer 鉴权**：自动生成 16 位 token，支持开关；未启用时任意局域网客户端均可连接。
- **工作区沙箱**：通过系统文件夹选择器（SAF）限定文件操作根目录，避免越权访问。
- **桥接远端 MCP**：可将远端 MCP 服务（Streamable HTTP）的工具拉取到本地，合并为单一端点。
- **内网穿透**：基于 [bore.pub](https://github.com/ekzhang/bore) 协议，一键将本地端口暴露到公网。
- **悬浮窗控制**：可选悬浮窗快捷开关服务器；支持开机自启动。
- **特权命令**：集成 [Shizuku](https://shizuku.rikka.app/)，在已授权设备上以 shell 权限执行 `am`、`pm` 等命令。
- **内置 JavaScript 引擎**：基于 QuickJS（ES2020），提供 `mcp_javascript` 工具执行脚本自动化。
- **前台服务保活**：`FOREGROUND_SERVICE_TYPE_DATA_SYNC` 与可选 WakeLock，确保长时间后台运行。

---

## 工作原理

应用以 Android 前台服务（`McpServerService`）持有 `McpHttpServer`，在本地端口监听 HTTP 请求。收到符合 MCP 规范的 JSON-RPC 消息后，由 `McpHandler` 分发到 `ToolKit`，再路由至各 `ToolDefinition` 的处理函数，并将结果以 JSON 或 SSE 形式返回。

```mermaid
flowchart LR
    Client[MCP 客户端<br/>Claude Desktop / Cursor]
    Server[MCP 服务器<br/>McpHttpServer]
    Toolkit[ToolKit]
    Tools[ToolDefinitions]
    Device[设备能力]
    Client <-->|Streamable HTTP / SSE| Server
    Server --> Toolkit
    Toolkit --> Tools
    Tools --> Device
```

### HTTP 端点

| 方法     | 路径                | 说明                                          |
| -------- | ------------------- | --------------------------------------------- |
| `POST`   | `/mcp`              | 主 JSON-RPC 入口（Streamable HTTP）            |
| `GET`    | `/sse`              | Server-Sent Events 长连接                     |
| `GET`    | `/tools`            | 列出所有已注册工具（含桥接工具）              |
| `GET`    | `/info`、`/meta`    | 服务器元信息（端口、协议版本、能力）          |
| `GET`    | `/`、`/status`      | 人类可读运行状态                              |
| `GET`    | `/health`           | 健康检查与运行时长                            |

服务器实现的 JSON-RPC 方法包括 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`、`resources/list`、`prompts/list` 等。

---

## 工具清单

服务器启动时由 `ToolKit.init` 注册以下内置工具。所有工具均返回 JSON 对象；返回错误时填充 `isError: true` 并附带标准化错误码（详见 `ErrorCodes.kt`）。

<details>
<summary>展开查看全部 45 个工具（8 个分类）</summary>

### 应用管理

| 工具名            | 说明                                                                  |
| ----------------- | --------------------------------------------------------------------- |
| `app_info`        | 获取单个应用的包名、版本、安装时间、UID、target/minSdk 等元信息       |
| `stop_app`        | 强制停止指定应用；Shizuku/root 下走 `am force-stop`，否则回退到 `killBackgroundProcesses` |
| `installed_apps`  | 枚举已安装应用（包名、名称、版本、安装时间，支持按关键字过滤）         |

### 通讯交互

| 工具名              | 说明                                                                    |
| ------------------- | ----------------------------------------------------------------------- |
| `clipboard`         | 读取或写入系统剪贴板（`operation` 为 `read` / `write`）                |
| `send_notification` | 发送系统通知，支持 `title` / `content` / `priority`（low/normal/high/max） |

### 设备信息

| 工具名        | 说明                                                  |
| ------------- | ----------------------------------------------------- |
| `device_info` | 设备品牌、型号、Android 版本、SDK 等级、指纹等         |
| `screen_info` | 屏幕分辨率、密度、刷新率、亮度等                      |

### 文件操作（限于工作区根目录下）

| 工具名             | 说明                                                                       |
| ------------------ | -------------------------------------------------------------------------- |
| `get_workspace`    | 返回当前工作区路径、显示名、是否 SAF 模式                                   |
| `list_files`       | 列出目录内容，支持递归深度与扩展名过滤                                     |
| `read_file`        | 读取文本文件（可指定编码、起始位置、长度）                                  |
| `write_file`       | 写入文本或 base64 二进制内容到文件                                          |
| `edit`             | 按匹配文本精确替换（`occurrence` 从 1 开始，`-1` 表示全部替换）            |
| `create_directory` | 新建目录（自动创建父目录）                                                  |
| `copy_file`        | 复制文件或目录                                                              |
| `move_file`        | 移动或重命名                                                                |
| `delete_file`      | 删除文件或目录（目录需 `recursive=true`）；操作不可逆                       |
| `file_info`        | 查询大小、权限、修改时间，`hash=true` 时计算 MD5/SHA256                     |
| `touch`            | 新建空文件或更新时间戳；`truncate=true` 清空内容                            |
| `compare_files`    | 对比两个文件，返回基于行的简单 diff                                          |
| `file_search`      | 按文件名 glob 或文件内容关键字（UTF-8 文本）递归搜索                        |
| `compress_zip`     | 将文件或目录压缩为 zip                                                       |
| `extract_zip`      | 解压 zip 到目标目录                                                          |
| `web_download`     | 从远程 URL 下载到工作区，支持大文件                                          |

### 元信息

| 工具名        | 说明                                                                |
| ------------- | ------------------------------------------------------------------- |
| `help`        | 返回总体说明与可用工具列表（按分类组织）                            |
| `tools_list`  | 列出全部工具及参数 schema                                            |
| `stats`       | 调用次数、运行时长、错误次数、日志条数等                            |
| `health`      | 健康检查：运行状态、端口、隧道、工作区、内存/存储                   |
| `batch`       | 顺序批量调用多个工具（最多 20 个），失败不中断                      |
| `continue`    | 分批处理文件或任务（size/count/read_head/read_tail），支持分页      |

### 网络请求

| 工具名          | 说明                                                                                  |
| --------------- | ------------------------------------------------------------------------------------- |
| `http_request`  | 通用 HTTP(S) 客户端（GET/POST/PUT/PATCH/DELETE/HEAD），支持 `body` / `bodyJson` / `form` |
| `shorten_url`   | 调用 tinyurl 服务生成短链接                                                            |

### 脚本执行

| 工具名            | 说明                                                                                                       |
| ----------------- | ---------------------------------------------------------------------------------------------------------- |
| `mcp_javascript`  | 在沙箱内执行 JavaScript（QuickJS ES2020），支持 `console.log/info/warn/error`；运行于应用沙箱，无法访问系统能力 |

### 系统管理

| 工具名              | 说明                                                                                                                              |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `battery`           | 电池电量、温度、电压、健康度、充电状态、电池优化与 WakeLock 状态                                                                  |
| `storage_info`      | 内部与外部存储容量                                                                                                                |
| `locale_info`       | 系统语言与时区                                                                                                                    |
| `system_properties` | 读取 `ro.build.*`、`ro.config.*` 等系统属性                                                                                       |
| `running_processes` | 枚举当前正在运行的进程（受权限限制）                                                                                              |
| `shell`             | 执行 Shell 命令；`mode` 支持 `app`（默认，沙箱）/ `shizuku`（需授权）/ `auto`（自动尝试 root→Shizuku→应用沙箱）                  |
| `shizuku`           | 查看 Shizuku 权限授予状态                                                                                                        |

### 实用工具

| 工具名              | 说明                                                                                                       |
| ------------------- | ---------------------------------------------------------------------------------------------------------- |
| `time_now`          | 返回当前时间戳（秒/毫秒）与格式化时间                                                                     |
| `check_permission`  | 检查运行时权限授予状态（传入权限名，如 `READ_SMS`）                                                       |
| `permission_state`  | 汇总关键权限授予状态                                                                                       |
| `json_format`       | JSON 美化（`pretty`，默认）/ 压缩（`minify`）/ 校验（`validate`）                                          |
| `text_convert`      | 文本大小写、修剪、反转、行数统计等                                                                         |
| `base64_encode`     | 文本与 Base64 互转（`decode=true` 时执行解码）                                                             |
| `decrypt_xor`       | XOR 加解密，支持单字节与多字节密钥，输入可选 hex/base64/utf8                                              |

</details>

---

## 客户端接入

任何支持 MCP Streamable HTTP 传输的客户端均可连接。以下示例以 `curl` 与典型 JSON-RPC 请求演示。

### initialize

```bash
curl -X POST http://<设备IP>:1145/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {"name": "example", "version": "1.0"}
    }
  }'
```

服务器响应后会下发 `Mcp-Session-Id` 头，后续请求需原样回传。

### 列出工具

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

### 调用工具

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "device_info",
    "arguments": {}
  }
}
```

### 客户端配置示例（Claude Desktop / Cline）

```json
{
  "mcpServers": {
    "android-device": {
      "type": "streamableHttp",
      "url": "http://192.168.1.10:1145/mcp"
    }
  }
}
```

启用 token 鉴权时，需在客户端请求头附加：

```
Authorization: Bearer <token>
```

`<token>` 见应用设置页 “鉴权 token”。

---

## 权限说明

应用声明以下权限，对应用途如下：

| 权限                                       | 用途                                                                |
| ------------------------------------------ | ------------------------------------------------------------------- |
| `INTERNET`                                 | HTTP 服务器监听与远端 MCP 通信                                      |
| `ACCESS_NETWORK_STATE`                     | 检测网络可用性                                                      |
| `ACCESS_WIFI_STATE`                        | 读取当前 Wi-Fi SSID（用于状态显示）                                 |
| `FOREGROUND_SERVICE`                       | 启动前台服务以保证后台存活                                          |
| `FOREGROUND_SERVICE_DATA_SYNC`             | Android 14+ 要求声明前台服务类型                                    |
| `POST_NOTIFICATIONS`                       | 显示前台服务通知（Android 13+）                                     |
| `WAKE_LOCK`                                | 可选唤醒锁                                                          |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`     | 申请忽略电池优化                                                    |
| `RECEIVE_BOOT_COMPLETED`                   | 开机自启                                                            |
| `SYSTEM_ALERT_WINDOW`                      | 悬浮窗权限                                                          |
| `USE_FULL_SCREEN_INTENT`                   | 通知全屏意图                                                        |
| `READ_PHONE_STATE`                         | 部分系统信息读取                                                    |
| `READ/WRITE_EXTERNAL_STORAGE`              | 兼容旧版本存储访问（`maxSdkVersion` 32 / 28）                       |
| `MANAGE_EXTERNAL_STORAGE`                  | 旧版本 “所有文件访问” 权限                                          |
| `QUERY_ALL_PACKAGES`                       | `installed_apps` 工具枚举全部应用                                   |

> [!WARNING]
> `MANAGE_EXTERNAL_STORAGE` 与 `QUERY_ALL_PACKAGES` 属于高敏感权限，可能影响应用商店上架。请评估实际使用场景后再决定是否对外分发。

---

## 致谢

本项目在设计与实现过程中参考或直接使用了以下开源项目，谨此致谢：

- [Model Context Protocol](https://modelcontextprotocol.io/) — Anthropic 提出的开放协议规范
- [Shizuku](https://shizuku.rikka.app/) — 以普通应用身份获取系统级 API 能力
- [QuickJS Android](https://github.com/taoweiji/quickjs-android) — 嵌入式 JavaScript 引擎（ES2020）
- [bore](https://github.com/ekzhang/bore) — 内网穿透隧道协议实现参考
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — 现代 Android UI 工具包
- [Gson](https://github.com/google/gson) — JSON 序列化库
- [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) — Kotlin 协程支持

---

## 许可证

本项目基于 **GNU General Public License v3.0**（GPL-3.0）开源。

您可以自由地使用、修改和分发本软件，但所有衍生作品必须以相同许可证开源，并在显著位置保留原始版权与许可证声明。