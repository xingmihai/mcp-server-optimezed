package com.mcp.server.bridge

import android.content.Context
import com.mcp.server.server.LogStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 桥接配置持久化 + 远端 MCP 服务客户端。
 *
 * 通过标准 MCP Streamable HTTP（POST /mcp，JSON-RPC）与远端服务通信：
 * 1. initialize（携带 protocolVersion / capabilities / clientInfo，部分严格服务缺参会直接拒绝）
 * 2. tools/list
 * 3. tools/call
 *
 * 支持 Bearer token 认证（仅在填写 token 时发送）；兼容 application/json 与
 * text/event-stream（SSE）两种响应；支持 Streamable HTTP 的 Mcp-Session-Id 会话。
 */
object BridgeManager {

    private const val PREFS = "mcp_bridges"

    /** 自动分配前缀的计数（MCP1_、MCP2_ ...） */
    private var prefixCounter: Int = 1

    /** 进程内已存在的前缀集合，用于自动命名时避免重复 */
    private val usedPrefixes: MutableSet<String> = HashSet()

    /** 递增的 JSON-RPC id */
    private val rpcId = AtomicInteger(1)

    /** 每个桥接的 Streamable HTTP 会话 id（initialize 响应头 Mcp-Session-Id） */
    private val sessions = ConcurrentHashMap<String, String>()

    /** 每个桥接已协商的协议版本 */
    private val protocolVersions = ConcurrentHashMap<String, String>()

    // ===== 持久化 =====

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): List<BridgeConfig> {
        val raw = prefs(context).getString("bridges", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<BridgeConfig>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(BridgeConfig(
                    id = o.optString("id", "b$i"),
                    name = o.optString("name", "未命名"),
                    url = o.optString("url", ""),
                    token = if (o.has("token")) o.optString("token") else null,
                    prefix = o.optString("prefix", ""),
                    enabled = o.optBoolean("enabled", true),
                ))
            }
            // 计算下一个自动前缀序号，并收集已使用的前缀
            var max = 0
            usedPrefixes.clear()
            for (b in out) {
                if (b.prefix.isNotEmpty()) usedPrefixes.add(b.prefix)
                val n = b.prefix.removeSuffix("_").removePrefix("MCP").toIntOrNull()
                if (n != null && n > max) max = n
            }
            prefixCounter = max + 1
            out
        } catch (e: Exception) {
            LogStore.warn("桥接配置解析失败: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, list: List<BridgeConfig>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("name", b.name)
                put("url", b.url)
                if (b.token != null) put("token", b.token)
                put("prefix", b.prefix)
                put("enabled", b.enabled)
            })
        }
        prefs(context).edit().putString("bridges", arr.toString()).apply()
    }

    /**
     * 自动分配下一个未使用的前缀：MCP1_、MCP2_ ...
     * 会检查当前 MCP 桥接列表中是否已存在相同命名，若已存在则跳过继续递增。
     */
    @Synchronized
    fun nextPrefix(): String {
        var p: String
        do {
            p = "MCP${prefixCounter}_"
            prefixCounter++
        } while (p in usedPrefixes)
        usedPrefixes.add(p)
        return p
    }

    /** 生成唯一 id */
    fun newId(): String = "b${System.currentTimeMillis()}${(Math.random() * 1000).toInt()}"

    // ===== 工具缓存（进程内） =====

    @Volatile
    var cachedTools: List<BridgeToolInfo> = emptyList()
        private set

    /** 是否已拉取过工具列表 */
    @Volatile
    var loaded: Boolean = false
        private set

    /**
     * 重新加载桥接配置并拉取所有（启用的）桥接服务工具列表。
     * 返回 (桥接列表, 失败信息 map bridgeId->error)
     */
    @Synchronized
    fun refresh(context: Context): Pair<List<BridgeConfig>, Map<String, String>> {
        val bridges = load(context)
        val tools = mutableListOf<BridgeToolInfo>()
        val errors = mutableMapOf<String, String>()
        for (b in bridges) {
            if (!b.enabled) continue
            val result = fetchToolsDetailed(b)
            if (result == null) {
                errors[b.id] = lastError
            } else {
                for (t in result) {
                    tools.add(BridgeToolInfo(
                        id = "${b.id}:${t.originalName}",
                        bridgeId = b.id,
                        originalName = t.originalName,
                        name = "${b.prefix}${t.originalName}",
                        description = t.description,
                        inputSchema = t.inputSchema,
                        enabled = true,
                    ))
                }
            }
        }
        cachedTools = tools
        loaded = true
        return bridges to errors
    }

    /** 清空缓存（配置变更后调用） */
    @Synchronized
    fun invalidate() {
        cachedTools = emptyList()
        loaded = false
    }

    fun findTool(name: String): BridgeToolInfo? = cachedTools.find { it.name == name }

    // ===== 远端调用 =====

    /** 最后一次失败的具体原因（供 UI 展示） */
    @Volatile
    var lastError: String = "未知错误"

    /** 拉取远端工具列表（不含前缀），失败返回 null */
    fun fetchTools(b: BridgeConfig): List<BridgeToolInfo>? = fetchToolsDetailed(b)

    private fun fetchToolsDetailed(b: BridgeConfig): List<BridgeToolInfo>? {
        if (!ensureInitialized(b)) return null
        val toolsResult = jsonRpc(b, "tools/list", JSONObject()) ?: return null
        val arr = toolsResult.optJSONArray("tools") ?: run {
            lastError = "响应中没有 tools 数组（收到: ${toolsResult.toString().take(200)}）"
            return null
        }
        val out = mutableListOf<BridgeToolInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val originalName = o.optString("name", "")
            if (originalName.isEmpty()) continue
            val schema = o.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
            out.add(BridgeToolInfo(
                id = "${b.id}:$originalName",
                bridgeId = b.id,
                originalName = originalName,
                name = "${b.prefix}${originalName}",
                description = o.optString("description", ""),
                inputSchema = schema,
            ))
        }
        return out
    }

    /** 调用远端工具 */
    fun call(b: BridgeConfig, toolName: String, arguments: JSONObject): BridgeCallResult? {
        if (!ensureInitialized(b)) return null
        val params = JSONObject()
            .put("name", toolName)
            .put("arguments", arguments)
        val result = jsonRpc(b, "tools/call", params) ?: return null
        val content = result.optJSONArray("content")
        val text = if (content != null && content.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i)
                if (c != null && c.optString("type", "") == "text") {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(c.optString("text", ""))
                }
            }
            sb.toString()
        } else result.toString(2)
        val isError = result.optBoolean("isError", false)
        return BridgeCallResult(
            ok = !isError,
            isError = isError,
            text = text,
            raw = result.toString(2),
        )
    }

    /**
     * 确保已完成 initialize 握手（同一进程内每个桥接只握手一次）。
     * 失败返回 false，lastError 已设置具体原因。
     */
    private fun ensureInitialized(b: BridgeConfig): Boolean {
        if (protocolVersions.containsKey(b.id)) return true
        val params = JSONObject()
            .put("protocolVersion", "2025-03-26")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject()
                .put("name", "android-mcp-bridge")
                .put("version", "1.0.0"))
        var r = jsonRpc(b, "initialize", params)
        if (r == null && (lastError.contains("protocol", true) || lastError.contains("version", true))) {
            // 部分服务只支持旧协议版本，重试
            params.put("protocolVersion", "2024-11-05")
            r = jsonRpc(b, "initialize", params)
        }
        if (r == null) return false
        protocolVersions[b.id] = r.optString("protocolVersion", "2025-03-26")
        return true
    }

    /** 执行一次 JSON-RPC 请求（同步，超时 10s），返回 result 对象；失败返回 null */
    private fun jsonRpc(b: BridgeConfig, method: String, params: JSONObject): JSONObject? {
        return try {
            val req = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", rpcId.incrementAndGet())
                .put("method", method)
                .put("params", params)
            var resp = postOnce(b, req)
            if (resp != null) {
                val err = resp.optJSONObject("error")
                if (err != null) {
                    val msg = err.optString("message", "远端返回错误")
                    if (sessions.containsKey(b.id) && msg.contains("session", true)) {
                        // 服务端会话已失效：重新握手后重试一次
                        sessions.remove(b.id)
                        protocolVersions.remove(b.id)
                        if (!ensureInitialized(b)) return null
                        resp = postOnce(b, req)
                        if (resp != null) {
                            val err2 = resp.optJSONObject("error")
                            if (err2 != null) {
                                lastError = err2.optString("message", "远端返回错误")
                                LogStore.warn("桥接 ${b.name} $method 错误: $lastError")
                                return null
                            }
                        }
                    } else {
                        lastError = msg
                        LogStore.warn("桥接 ${b.name} $method 错误: $lastError")
                        return null
                    }
                }
            }
            val finalResp = resp ?: return null
            finalResp.optJSONObject("result")
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            LogStore.warn("桥接 ${b.name} $method 异常: $lastError")
            null
        }
    }

    /** 发送一次 HTTP POST；地址缺少 /mcp 且返回 404 时自动补 /mcp 重试一次 */
    private fun postOnce(b: BridgeConfig, req: JSONObject): JSONObject? {
        val attempts = mutableListOf(b.url.trim())
        val base = b.url.trim().removeSuffix("/")
        try {
            val path = URI(base).path ?: ""
            if (path.isEmpty() || path == "/") {
                attempts.add("$base/mcp")
            }
        } catch (_: Exception) {
        }
        var i = 0
        while (i < attempts.size) {
            val resp = tryPost(b, attempts[i], req)
            if (resp != null) return resp
            if (lastError.startsWith("HTTP 404") && i < attempts.size - 1) {
                i++
                continue
            }
            return null
        }
        return null
    }

    private fun tryPost(b: BridgeConfig, url: String, req: JSONObject): JSONObject? {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                // 兼容两种响应格式：优先 JSON，也接受 SSE
                conn.setRequestProperty("Accept", "application/json, text/event-stream")
                val token = b.token?.trim()
                if (!token.isNullOrEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }
                // Streamable HTTP 会话：initialize 后带上服务端下发的会话 id
                sessions[b.id]?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
                conn.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    lastError = "HTTP $code"
                    // 读取错误响应体，尽量给出具体原因
                    try {
                        val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        if (!errBody.isNullOrBlank()) {
                            lastError = "HTTP $code: ${errBody.take(200)}"
                        }
                    } catch (_: Exception) {
                    }
                    LogStore.warn("桥接 ${b.name} POST $url 失败: $lastError")
                    return null
                }
                // 记录服务端下发的会话 id（后续请求需要带上）
                val sid = conn.getHeaderField("Mcp-Session-Id")
                if (!sid.isNullOrBlank()) {
                    sessions[b.id] = sid.trim()
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (body.isBlank()) {
                    lastError = "空响应"
                    return null
                }
                // 兼容 SSE 响应：去掉 event:/data: 前缀
                var jsonText = body.trim()
                if (jsonText.contains("\ndata:") || jsonText.startsWith("data:")) {
                    val sb = StringBuilder()
                    for (line in jsonText.lineSequence()) {
                        val t = line.trim()
                        if (t.startsWith("data:")) sb.append(t.removePrefix("data:").trim())
                    }
                    jsonText = sb.toString()
                    if (jsonText.isBlank()) {
                        lastError = "SSE 响应中没有 data 内容"
                        return null
                    }
                }
                JSONObject(jsonText)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            LogStore.warn("桥接 ${b.name} POST $url 异常: $lastError")
            null
        }
    }
}
