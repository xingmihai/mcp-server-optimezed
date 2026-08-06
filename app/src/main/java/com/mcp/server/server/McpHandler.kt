package com.mcp.server.server

import com.mcp.server.tools.ToolKit
import org.json.JSONObject

/**
 * 处理 MCP (Model Context Protocol) JSON-RPC 消息。
 * 会话会持久化 protocolVersion 与 clientInfo。
 */
class McpHandler(
    private val onToolCall: (String, JSONObject?) -> Unit = { _, _ -> },
) {
    data class Session(
        var protocolVersion: String? = null,
        var clientInfo: JSONObject? = null,
        var initialized: Boolean = false,
    )

    @Volatile
    var session = Session()

    fun reset() {
        session = Session()
    }

    /** 处理请求/通知，返回需要发送给客户端的响应文本（可能为 null） */
    fun handle(message: RpcMessage): String? = when (message) {
        is RpcRequest -> handleRequest(message)
        is RpcNotification -> {
            handleNotification(message)
            null
        }
        is RpcResponse -> null
    }

    private fun handleRequest(req: RpcRequest): String {
        return try {
            when (req.method) {
                "initialize" -> {
                    val p = req.params ?: JSONObject()
                    session.protocolVersion = p.optString("protocolVersion")
                    session.clientInfo = p.optJSONObject("clientInfo")
                    session.initialized = true
                    val caps = JSONObject().apply {
                        put("tools", JSONObject().put("listChanged", false))
                    }
                    val serverInfo = JSONObject().apply {
                        put("name", "android-mcp-server")
                        put("version", "1.0.0")
                    }
                    RpcCodec.encodeResponse(req.id, JSONObject().apply {
                        put("protocolVersion", session.protocolVersion ?: "2024-11-05")
                        put("capabilities", caps)
                        put("serverInfo", serverInfo)
                        put("instructions", "这是一个运行在 Android 手机上的本地 MCP 服务器。所有操作直接作用于手机本身。" +
                            "文件操作仅允许访问工作区目录内的文件（使用 get_workspace 查看当前工作区，set_workspace 修改工作区）。" +
                            "文件路径支持绝对路径（如 /sdcard/...）。敏感操作需要先在 App 中授予相应权限。" +
                            "操作必须使用以工作区为根的绝对路径。")
                    })
                }
                "notifications/initialized" -> RpcCodec.encodeResponse(req.id)
                "ping" -> RpcCodec.encodeResponse(req.id, JSONObject())
                "tools/list" -> RpcCodec.encodeResponse(req.id, ToolKit.toMcpSchema())
                "tools/call" -> handleToolCall(req)
                "shutdown" -> RpcCodec.encodeResponse(req.id, JSONObject())
                else -> RpcCodec.encodeResponse(req.id, null,
                    RpcError(RpcCodec.METHOD_NOT_FOUND, "未知方法: ${req.method}"))
            }
        } catch (e: Exception) {
            RpcCodec.encodeResponse(req.id, null,
                RpcError(RpcCodec.INTERNAL_ERROR, "服务器内部错误: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun handleToolCall(req: RpcRequest): String {
        val p = req.params ?: JSONObject()
        val name = p.optString("name", "")
        val args = p.optJSONObject("arguments") ?: JSONObject()
        // 先尝试本地工具
        val tool = ToolKit.find(name)
        if (tool != null) {
            if (!ToolKit.isEnabled(name)) {
                return RpcCodec.encodeResponse(req.id, null,
                    RpcError(RpcCodec.METHOD_NOT_FOUND, "工具已禁用: $name",
                        JSONObject().put("errorCode", com.mcp.server.tools.ErrorCodes.TOOL_DISABLED)))
            }
            // 参数校验：错误直接返回 INVALID_PARAMS + allowedValues/example 用法提示
            val validationError = com.mcp.server.tools.ParamValidator.validate(args, tool.inputSchema)
            if (validationError != null) {
                com.mcp.server.tools.MetaTools.recordCall(name, true)
                return RpcCodec.encodeResponse(req.id, null,
                    RpcError(RpcCodec.INVALID_PARAMS, "工具 $name 参数错误: $validationError",
                        JSONObject().put("errorCode", com.mcp.server.tools.ErrorCodes.INVALID_PARAMS)
                            .put("toolName", name)))
            }
            onToolCall(name, args)
            com.mcp.server.tools.MetaTools.recordCall(name, false)
            return try {
                val result = tool.handler(args)
                val contentArr = org.json.JSONArray()
                val text = when (result) {
                    is JSONObject -> result.toString(2)
                    is String -> result
                    null -> "null"
                    else -> result.toString()
                }
                contentArr.put(JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                })
                val resultObj = JSONObject().put("content", contentArr)
                if (result is JSONObject && result.has("isError")) {
                    resultObj.put("isError", result.optBoolean("isError"))
                }
                // 工具内部错误（带 errorCode）透传错误码，方便客户端精确处理
                if (result is JSONObject && result.has("errorCode") && result.has("error")) {
                    resultObj.put("isError", true)
                    resultObj.put("errorCode", result.optString("errorCode"))
                    if (result.has("hint")) resultObj.put("hint", result.optString("hint"))
                }
                RpcCodec.encodeResponse(req.id, resultObj)
            } catch (e: Exception) {
                com.mcp.server.tools.MetaTools.recordCall(name, true)
                val contentArr = org.json.JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", "工具执行失败: ${e.message ?: e.javaClass.simpleName}")
                })
                RpcCodec.encodeResponse(req.id, JSONObject()
                    .put("content", contentArr)
                    .put("isError", true)
                    .put("errorCode", com.mcp.server.tools.ErrorCodes.INTERNAL))
            }
        }
        // 否则尝试桥接 MCP 服务工具
        return handleBridgeToolCall(req, name, args)
    }

    /** 调用桥接 MCP 服务（带前缀）的工具 */
    private fun handleBridgeToolCall(req: RpcRequest, name: String, args: JSONObject): String {
        val bridgeTool = com.mcp.server.bridge.BridgeManager.findTool(name)
        if (bridgeTool == null) {
            return RpcCodec.encodeResponse(req.id, null,
                RpcError(RpcCodec.METHOD_NOT_FOUND, "未知工具: $name",
                    JSONObject().put("errorCode", com.mcp.server.tools.ErrorCodes.UNKNOWN_TOOL)))
        }
        if (!bridgeTool.enabled || !ToolKit.isBridgeEnabled(bridgeTool.id)) {
            return RpcCodec.encodeResponse(req.id, null,
                RpcError(RpcCodec.METHOD_NOT_FOUND, "工具已禁用: $name",
                    JSONObject().put("errorCode", com.mcp.server.tools.ErrorCodes.TOOL_DISABLED)))
        }
        // 桥接工具参数校验：错误时返回 INVALID_PARAMS + 用法提示
        val bridgeValidation = com.mcp.server.tools.ParamValidator.validate(args, bridgeTool.inputSchema)
        if (bridgeValidation != null) {
            com.mcp.server.tools.MetaTools.recordCall(name, true)
            return RpcCodec.encodeResponse(req.id, null,
                RpcError(RpcCodec.INVALID_PARAMS, "工具 $name 参数错误: $bridgeValidation",
                    JSONObject().put("errorCode", com.mcp.server.tools.ErrorCodes.INVALID_PARAMS)
                        .put("toolName", name)))
        }
        val bridge = com.mcp.server.bridge.BridgeManager.load(com.mcp.server.server.McpAppCtx.app)
            .find { it.id == bridgeTool.bridgeId }
        if (bridge == null || !bridge.enabled) {
            return RpcCodec.encodeResponse(req.id, null,
                RpcError(RpcCodec.METHOD_NOT_FOUND, "桥接服务不可用: $name",
                    JSONObject().put("errorCode", com.mcp.server.tools.ErrorCodes.BRIDGE_UNREACHABLE)))
        }
        onToolCall(name, args)
        com.mcp.server.tools.MetaTools.recordCall(name, false)
        return try {
            val result = com.mcp.server.bridge.BridgeManager.call(bridge, bridgeTool.originalName, args)
            if (result == null) {
                val contentArr = org.json.JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", "桥接调用失败: 无法连接远端 MCP 服务（${bridge.name}）")
                })
                RpcCodec.encodeResponse(req.id, JSONObject()
                    .put("content", contentArr)
                    .put("isError", true)
                    .put("errorCode", com.mcp.server.tools.ErrorCodes.BRIDGE_UNREACHABLE))
            } else {
                val contentArr = org.json.JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", result.text)
                })
                RpcCodec.encodeResponse(req.id, JSONObject()
                    .put("content", contentArr)
                    .put("isError", result.isError))
            }
        } catch (e: Exception) {
            com.mcp.server.tools.MetaTools.recordCall(name, true)
            val contentArr = org.json.JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", "桥接工具执行失败: ${e.message ?: e.javaClass.simpleName}")
            })
            RpcCodec.encodeResponse(req.id, JSONObject()
                .put("content", contentArr)
                .put("isError", true)
                .put("errorCode", com.mcp.server.tools.ErrorCodes.BRIDGE_UNREACHABLE))
        }
    }

    private fun handleNotification(notif: RpcNotification) {
        when (notif.method) {
            "notifications/initialized" -> {}
            "notifications/cancelled" -> {}
            else -> {}
        }
    }
}
