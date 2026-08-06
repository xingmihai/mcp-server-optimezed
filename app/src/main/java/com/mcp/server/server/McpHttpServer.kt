package com.mcp.server.server

import com.mcp.server.tools.ToolKit
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量 HTTP 服务器，实现 MCP Streamable HTTP 传输。
 * - GET /sse   → SSE 事件流（客户端可用）
 * - POST /mcp  → JSON-RPC 请求（application/json 或 text/event-stream）
 * - GET  /     → 服务器状态 JSON
 */
class McpHttpServer(
    private val port: Int,
    private val token: String,
    private val onLog: (String) -> Unit = {},
    private val onToolCall: (String, JSONObject?) -> Unit = { _, _ -> },
) {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val handler = McpHandler(onToolCall)
    private val clients = java.util.concurrent.ConcurrentHashMap<String, Client>()

    class Client {
        val queue = java.util.concurrent.LinkedBlockingQueue<String>()
        @Volatile var dead = false
        val id: String = UUID.randomUUID().toString()
    }

    fun start() {
        if (running.get()) return
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
            }
            running.set(true)
            LogStore.info("MCP 服务器已启动: 0.0.0.0:$port")
            executor.execute {
                while (running.get()) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.execute { handleConnection(socket) }
                    } catch (e: Exception) {
                        if (running.get()) onLog("接受连接失败: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            LogStore.error("服务器启动失败: ${e.message}")
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        for (c in clients.values) c.dead = true
        clients.clear()
        handler.reset()
        executor.shutdownNow()
        LogStore.info("MCP 服务器已停止")
    }

    val isRunning: Boolean get() = running.get()

    fun statusJson(): JSONObject = Net.statusJson(
        McpAppCtx.app, running.get(), android.os.Process.myPid().toLong())

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 300_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0]
            val path = parts[1]

            // 读取请求头
            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (true) {
                line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            contentLength = headers["content-length"]?.toIntOrNull() ?: 0

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            when {
                method == "GET" && path == "/sse" -> handleSse(socket, headers)
                method == "POST" && (path == "/mcp" || path == "/mcp/") -> handleMcp(socket, headers, body)
                method == "GET" && (path == "/" || path == "/status") -> handleStatus(socket)
                method == "GET" && (path == "/info" || path == "/meta") -> handleInfo(socket)
                method == "GET" && path == "/health" -> handleHealth(socket)
                method == "GET" && path == "/tools" -> handleTools(socket)
                else -> sendResponse(socket, 404, "application/json",
                    RpcCodec.encodeResponse(null, null, RpcError(RpcCodec.METHOD_NOT_FOUND, "Not Found")), headers)
            }
            LogStore.info("$method $path -> ${if (socket.isClosed) "已响应" else "已处理"}")
        } catch (e: Exception) {
            if (running.get()) onLog("连接处理失败: ${e.message}")
            LogStore.warn("连接处理异常: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** 简易鉴权：Bearer token 或 X-API-Key 头；未开启令牌时直接放行 */
    private fun authorized(headers: Map<String, String>): Boolean {
        if (!Settings.tokenEnabled(McpAppCtx.app)) return true
        val auth = headers["authorization"] ?: return false
        return auth == "Bearer $token" || auth == "Bearer ${md5(token)}"
    }

    private fun handleSse(socket: Socket, headers: Map<String, String>) {
        if (!authorized(headers)) {
            sendResponse(socket, 401, "application/json",
                RpcCodec.encodeResponse(null, null, RpcError(-32001, "未授权: 需要 Bearer Token")), headers)
            return
        }
        val client = Client()
        clients[client.id] = client
        LogStore.info("SSE 客户端连接: ${client.id.take(8)}...")
        try {
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: text/event-stream\r\n")
            writer.write("Cache-Control: no-cache\r\n")
            writer.write("Connection: keep-alive\r\n")
            writer.write("Access-Control-Allow-Origin: *\r\n")
            writer.write("\r\n")
            writer.flush()

            // 发送 endpoint 事件（标准 MCP SSE 流程）
            writer.write("event: endpoint\r\n")
            writer.write("data: /mcp?sessionId=${client.id}\r\n\r\n")
            writer.flush()

            while (!client.dead && running.get()) {
                val msg = client.queue.poll(10, java.util.concurrent.TimeUnit.SECONDS)
                if (msg != null) {
                    writer.write("event: message\r\n")
                    writer.write("data: $msg\r\n\r\n")
                    writer.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            client.dead = true
            clients.remove(client.id)
            LogStore.info("SSE 客户端断开: ${client.id.take(8)}...")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleMcp(socket: Socket, headers: Map<String, String>, body: String) {
        if (!authorized(headers)) {
            sendResponse(socket, 401, "application/json",
                RpcCodec.encodeResponse(null, null, RpcError(-32001, "未授权: 需要 Bearer Token")), headers)
            return
        }
        val accept = headers["accept"] ?: ""
        val contentType = headers["content-type"] ?: ""
        val sessionId = headers["mcp-session-id"] ?: headers["x-session-id"]

        val messages = RpcCodec.parse(body)
        if (messages == null) {
            sendResponse(socket, 400, "application/json",
                RpcCodec.encodeResponse(null, null, RpcError(RpcCodec.PARSE_ERROR, "无效的 JSON-RPC 消息")), headers)
            return
        }

        val responses = messages.mapNotNull { handler.handle(it) }
        val methodNames = messages.joinToString(", ") {
            (it as? RpcRequest)?.method ?: "?"
        }
        LogStore.info("收到 MCP 请求: $methodNames")

        // 建立 SSE 会话：客户端在 POST 时带上 Accept: text/event-stream
        val useSse = accept.contains("text/event-stream")
        if (useSse) {
            val client = Client()
            clients[client.id] = client
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: text/event-stream\r\n")
            writer.write("Cache-Control: no-cache\r\n")
            writer.write("mcp-session-id: ${client.id}\r\n")
            writer.write("Access-Control-Allow-Origin: *\r\n")
            writer.write("\r\n")
            for (r in responses) {
                writer.write("event: message\r\n")
                writer.write("data: $r\r\n\r\n")
            }
            writer.flush()

            // 保持连接并转发来自 GET /sse 的响应
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            try {
                while (!client.dead && running.get()) {
                    // 等待客户端关闭或超时
                    if (reader.ready()) reader.readLine()
                    Thread.sleep(100)
                }
            } catch (_: Exception) {
            } finally {
                client.dead = true
                clients.remove(client.id)
                try { socket.close() } catch (_: Exception) {}
            }
        } else {
            val bodyStr = if (responses.isEmpty()) {
                RpcCodec.encodeResponse(null, JSONObject()) // 空响应
            } else if (responses.size == 1) {
                responses[0]
            } else {
                org.json.JSONArray(responses.joinToString(",") { it }).toString()
            }
            sendResponse(socket, 200, "application/json", bodyStr, headers)
        }
    }

    private fun handleStatus(socket: Socket) {
        sendResponse(socket, 200, "application/json", statusJson().toString(), emptyMap())
    }

    /** GET /info（或 /meta）：服务器元信息，浏览器可直接访问 */
    private fun handleInfo(socket: Socket) {
        val ctx = McpAppCtx.app
        val j = JSONObject()
        j.put("ok", true)
        j.put("name", "android-mcp-server")
        j.put("version", "1.0.0")
        j.put("protocol", "MCP JSON-RPC 2.0")
        j.put("status", if (running.get()) "running" else "stopped")
        j.put("port", Settings.port(ctx))
        j.put("endpoint", "/mcp")
        j.put("sseEndpoint", "/sse")
        j.put("toolsEndpoint", "/tools")
        j.put("methods", org.json.JSONArray(listOf(
            "initialize", "notifications/initialized", "ping",
            "tools/list", "tools/call", "resources/list", "prompts/list",
        )))
        j.put("toolCount", ToolKit.enabledTools().size)
        j.put("tunnelActive", McpServerService.tunnelActive)
        j.put("tunnelUrl", McpServerService.tunnelUrl ?: JSONObject.NULL)
        j.put("workspace", com.mcp.server.tools.Workspace.display(ctx))
        j.put("device", Net.deviceName())
        j.put("hint", "POST JSON-RPC 到 /mcp；GET /mcp 带 Accept: text/event-stream 可建立 SSE 会话")
        sendResponse(socket, 200, "application/json", j.toString(2), emptyMap())
    }

    /** GET /health：健康检查 */
    private fun handleHealth(socket: Socket) {
        val ctx = McpAppCtx.app
        val j = JSONObject()
        j.put("ok", running.get())
        j.put("status", if (running.get()) "running" else "stopped")
        j.put("version", "1.0.0")
        j.put("port", Settings.port(ctx))
        j.put("uptimeSeconds", McpServerService.uptimeSeconds())
        j.put("tunnelActive", McpServerService.tunnelActive)
        j.put("tunnelUrl", McpServerService.tunnelUrl ?: JSONObject.NULL)
        j.put("time", System.currentTimeMillis())
        sendResponse(socket, 200, "application/json", j.toString(2), emptyMap())
    }

    /** GET /tools：可用工具列表（浏览器可直接查看） */
    private fun handleTools(socket: Socket) {
        sendResponse(socket, 200, "application/json", ToolKit.toMcpSchema().toString(2), emptyMap())
    }

    private fun sendResponse(socket: Socket, code: Int, contentType: String, body: String, reqHeaders: Map<String, String>) {
        try {
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            val statusText = when (code) {
                200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 500 -> "Internal Server Error"
                else -> "Error"
            }
            writer.write("HTTP/1.1 $code $statusText\r\n")
            writer.write("Content-Type: $contentType\r\n")
            writer.write("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
            writer.write("Access-Control-Allow-Origin: *\r\n")
            writer.write("Access-Control-Allow-Headers: authorization, content-type, mcp-session-id, x-session-id\r\n")
            writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.write(body)
            writer.flush()
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** 全局 Application 上下文引用（服务启动时设置） */
object McpAppCtx {
    @Volatile
    lateinit var app: android.content.Context
}
