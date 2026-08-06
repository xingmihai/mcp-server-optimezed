package com.mcp.server.server

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Minimal JSON-RPC 2.0 message model used by the MCP server.
 */
sealed class RpcMessage

data class RpcRequest(
    val id: Any?,                 // number or string
    val method: String,
    val params: JSONObject?,
) : RpcMessage()

data class RpcNotification(
    val method: String,
    val params: JSONObject?,
) : RpcMessage()

data class RpcResponse(
    val id: Any?,
    val result: JSONObject? = null,
    val error: RpcError? = null,
) : RpcMessage()

data class RpcError(
    val code: Int,
    val message: String,
    val data: JSONObject? = null,
)

object RpcCodec {

    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    fun parse(text: String): List<RpcMessage>? {
        return try {
            val root = JSONObject(text)
            listOf(parseObject(root))
        } catch (e: JSONException) {
            try {
                val arr = JSONArray(text)
                (0 until arr.length()).map { parseObject(arr.getJSONObject(it)) }
            } catch (e2: JSONException) {
                null
            }
        }
    }

    private fun parseObject(obj: JSONObject): RpcMessage {
        val id = if (obj.has("id")) obj.get("id") else null
        val method = if (obj.has("method")) obj.getString("method") else null
        val params = obj.optJSONObject("params")
        return if (method != null) {
            if (id != null && id !== JSONObject.NULL) RpcRequest(id, method, params) else RpcNotification(method, params)
        } else {
            val err = obj.optJSONObject("error")
            RpcResponse(
                id = id,
                result = obj.optJSONObject("result"),
                error = err?.let { RpcError(it.optInt("code"), it.optString("message"), it.optJSONObject("data")) }
            )
        }
    }

    fun encodeResponse(id: Any?, result: JSONObject? = null, error: RpcError? = null): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        if (id != null) obj.put("id", id) else obj.put("id", JSONObject.NULL)
        if (error != null) {
            val e = JSONObject()
            e.put("code", error.code)
            e.put("message", error.message)
            if (error.data != null) e.put("data", error.data)
            obj.put("error", e)
        } else {
            obj.put("result", result ?: JSONObject())
        }
        return obj.toString()
    }

    fun encodeNotification(method: String, params: JSONObject): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        obj.put("method", method)
        obj.put("params", params)
        return obj.toString()
    }

    fun parseId(text: String): Any? {
        val obj = JSONObject(text)
        val id = obj.get("id")
        return if (id === JSONObject.NULL) null else id
    }
}
