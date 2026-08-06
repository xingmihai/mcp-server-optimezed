package com.mcp.server.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** 网络请求类工具 */
object NetTools {

    fun register(sink: MutableList<ToolDefinition>) {
        sink.add(httpRequest().inCategory("网络请求"))
        sink.add(shortenUrl().inCategory("网络请求"))
        // web_request 已删除：功能并入 http_request（不注册别名）
    }

    private fun schema(props: List<Pair<String, String>>): JSONObject {
        val s = JSONObject()
        val p = JSONObject()
        for ((k, t) in props) p.put(k, JSONObject().put("type", t))
        s.put("type", "object")
        s.put("properties", p)
        s.put("required", JSONArray(listOf(props.first().first)))
        return s
    }

    private fun httpRequest(): ToolDefinition {
        return ToolDefinition(
            "http_request", "发起 HTTP(S) 请求。method 支持 GET/POST/PUT/PATCH/DELETE/HEAD；body 为字符串；bodyJson 为 JSON 对象（自动序列化并设置 Content-Type: application/json）；form 为表单字段",
            paramSchema(listOf(
                param("url", "string", required = true, example = "https://api.example.com/data", description = "请求地址（http/https）"),
                param("method", "string", example = "GET",
                    allowedValues = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"),
                    description = "HTTP 方法"),
                param("headers", "object", example = "{\"Authorization\":\"Bearer xxx\"}", description = "请求头"),
                param("body", "string", example = "hello", description = "字符串请求体"),
                param("bodyJson", "object", example = "{\"key\":\"value\"}", description = "JSON 请求体（自动设置 Content-Type: application/json）"),
                param("form", "object", example = "{\"field\":\"value\"}", description = "表单字段（application/x-www-form-urlencoded）"),
                param("timeoutMs", "integer", example = 15000, minimum = 1000, maximum = 120000, description = "超时时间（毫秒）"),
                param("followRedirects", "boolean", example = true, description = "是否跟随重定向"),
                param("maxResponseBytes", "integer", example = 1048576, minimum = 1024, maximum = 10485760, description = "最大响应字节数"),
            ), required = listOf("url"))
        ) { args ->
            val urlStr = args.optString("url", "")
            if (urlStr.isBlank()) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "url 不能为空",
                "url 为必填参数，例如: https://api.example.com")
            if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "仅支持 http/https 协议",
                    "url 必须以 http:// 或 https:// 开头")
            }
            val method = args.optString("method", "GET").uppercase()
            if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 method: $method",
                    "allowedValues: GET / POST / PUT / PATCH / DELETE / HEAD，例如: \"GET\"")
            }
            val timeout = args.optInt("timeoutMs", 15000).coerceIn(1000, 120000)
            val maxBytes = args.optInt("maxResponseBytes", 1_048_576).coerceIn(1024, 10_485_760)
            return@ToolDefinition try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = maxOf(timeout / 2, 10000)
                conn.readTimeout = timeout
                conn.instanceFollowRedirects = args.optBoolean("followRedirects", true)
                conn.setRequestProperty("User-Agent",
                    "MCP-AndroidServer/1.0 (${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE})")
                conn.setRequestProperty("Accept-Encoding", "gzip")

                val headers = args.optJSONObject("headers")
                if (headers != null) {
                    for (k in headers.keys()) conn.setRequestProperty(k, headers.getString(k))
                }

                var bodyBytes: ByteArray? = null
                var contentType: String? = null
                val bodyJson = args.optJSONObject("bodyJson")
                val form = args.optJSONObject("form")
                val bodyStr = args.optString("body", "")
                when {
                    bodyJson != null -> {
                        bodyBytes = bodyJson.toString().toByteArray(Charsets.UTF_8)
                        contentType = "application/json; charset=utf-8"
                    }
                    form != null && form.length() > 0 -> {
                        val sb = StringBuilder()
                        var first = true
                        for (k in form.keys()) {
                            if (!first) sb.append("&")
                            first = false
                            sb.append(URLEncoder.encode(k, "UTF-8"))
                                .append("=")
                                .append(URLEncoder.encode(form.getString(k), "UTF-8"))
                        }
                        bodyBytes = sb.toString().toByteArray(Charsets.UTF_8)
                        contentType = "application/x-www-form-urlencoded"
                    }
                    bodyStr.isNotEmpty() -> {
                        bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
                        contentType = headers?.optString("Content-Type") ?: "text/plain; charset=utf-8"
                    }
                }

                if (bodyBytes != null && method != "GET" && method != "HEAD") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", contentType)
                    conn.setRequestProperty("Content-Length", bodyBytes.size.toString())
                    DataOutputStream(conn.outputStream).use { it.write(bodyBytes); it.flush() }
                }

                val code = conn.responseCode
                val isError = code >= 400
                val input = if (isError) conn.errorStream else conn.inputStream
                val bytes = if (input != null) {
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    val buffered = java.io.BufferedInputStream(input)
                    if ("gzip" == conn.getHeaderField("Content-Encoding")) {
                        GZIPInputStream(buffered).use { gz ->
                            while (true) {
                                val n = gz.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) break
                                bos.write(buf, 0, n)
                            }
                        }
                    } else {
                        buffered.use { b ->
                            while (true) {
                                val n = b.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) break
                                bos.write(buf, 0, n)
                            }
                        }
                    }
                    bos.toByteArray()
                } else {
                    ByteArray(0)
                }
                conn.disconnect()

                JSONObject().apply {
                    put("status", code)
                    put("statusText", conn.responseMessage ?: "")
                    put("url", conn.url.toString())
                    put("bytes", bytes.size)
                    put("truncated", bytes.size >= maxBytes)
                    put("headers", JSONObject().apply {
                        for (i in 0 until (conn.headerFields?.size ?: 0)) {
                            val key = conn.getHeaderFieldKey(i) ?: continue
                            val value = conn.getHeaderField(i)
                            if (value != null) put(key, value)
                        }
                    })
                    put("body", String(bytes, Charsets.UTF_8))
                    if (code >= 400) {
                        put("errorCode", ErrorCodes.HTTP_ERROR)
                        put("error", "HTTP $code: ${conn.responseMessage ?: ""}")
                    }
                }
            } catch (e: Exception) {
                Err.of(ErrorCodes.NETWORK_ERROR, "请求失败: ${e.message}",
                    "检查网络连接、url 与超时设置")
            }
        }
    }

    private fun shortenUrl(): ToolDefinition {
        return ToolDefinition(
            "shorten_url", "使用 tinyurl 服务生成短链接",
            paramSchema(listOf(
                param("url", "string", required = true, example = "https://example.com/very/long/path", description = "要缩短的链接"),
            ), required = listOf("url"))
        ) { args ->
            val urlStr = args.optString("url", "")
            if (urlStr.isBlank()) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "url 不能为空",
                "url 为必填参数")
            return@ToolDefinition try {
                val conn = URL("https://tinyurl.com/api-create.php?url=" +
                    URLEncoder.encode(urlStr, "UTF-8")).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText().trim() }
                conn.disconnect()
                if (text.startsWith("http")) {
                    JSONObject().put("ok", true).put("original", urlStr).put("short", text)
                } else {
                    Err.of(ErrorCodes.NETWORK_ERROR, "短链接服务返回异常: $text",
                        "请稍后重试或检查网络")
                }
            } catch (e: Exception) {
                Err.of(ErrorCodes.NETWORK_ERROR, "请求失败: ${e.message}",
                    "检查网络连接")
            }
        }
    }
}
