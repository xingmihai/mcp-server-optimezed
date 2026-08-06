package com.mcp.server.tools

import org.json.JSONObject

/**
 * An MCP tool definition plus its implementation.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val category: String = "通用",
    /** Invoke the tool. Returns a JSON-serializable result value (already converted to JSON by ToolKit). */
    val handler: (JSONObject) -> Any?,
)
