package com.mcp.server.bridge

/** 一条桥接 MCP 服务配置 */
data class BridgeConfig(
    val id: String,
    val name: String,
    val url: String,
    val token: String? = null,
    /** 工具名前缀（如 MCP1_），可修改 */
    val prefix: String,
    /** 该桥接整体是否启用 */
    val enabled: Boolean = true,
) {
    fun withPrefix(p: String): BridgeConfig = copy(prefix = p)
    fun withEnabled(e: Boolean): BridgeConfig = copy(enabled = e)
}

/** 桥接远端的一个工具 */
data class BridgeToolInfo(
    val id: String,
    val bridgeId: String,
    val originalName: String,
    val name: String,
    val description: String,
    val inputSchema: org.json.JSONObject,
    /** 该工具是否允许调用（开关） */
    var enabled: Boolean = true,
)

/** 远端工具调用结果 */
data class BridgeCallResult(
    val ok: Boolean,
    val isError: Boolean,
    val text: String,
    val raw: String? = null,
)
