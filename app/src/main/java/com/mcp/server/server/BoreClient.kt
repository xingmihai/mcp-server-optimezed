package com.mcp.server.server

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * bore 内网穿透隧道客户端。
 *
 * 协议（与 bore 官方一致）：
 * - 控制连接：TCP 连到 server:7835，发送 `{"Hello":port}`（0 表示随机端口）
 * - 认证（可选）：服务器发 `{"Challenge":"<uuid>"}`，客户端回 `{"Authenticate":"<hmac>"}`
 * - 数据连接：收到 `{"Connection":"<id>"}` 后新开 TCP 到 server:7835，
 *   先（可选）认证，再发 `{"Accept":"<id>"}`，随后双向转发本地端口
 * - 心跳：约每 25s 发送 `"Heartbeat"` 保活
 *
 * 设计：
 * - 单一调度线程负责控制连接与重连（避免并发连接风暴）
 * - 每条数据连接独占两个转发线程，断流即关闭
 * - 心跳独立单线程，断线自动退出
 * - stop() 幂等，可安全重复调用
 */
class BoreClient(
    private val serverHost: String,
    private val localPort: Int,
    private val requestedRemotePort: Int = 0,
    private val secret: String? = null,
    private val callback: Callback,
) {

    interface Callback {
        fun onConnected(publicUrl: String, mcpUrl: String)
        fun onDisconnected()
        fun onError(message: String)
        fun onLog(message: String)
    }

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val heartbeatExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bore-heartbeat").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    @Volatile
    private var connected = false

    @Volatile
    private var assignedRemotePort = 0

    @Volatile
    private var controlSocket: Socket? = null

    /** 已建立的数据连接（用于 stop 时统一关闭） */
    private val dataSockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    /** 控制连接发送锁：心跳与主循环可能同时写 */
    private val writeLock = Any()

    val isRunning: Boolean get() = running
    val isConnected: Boolean get() = connected
    val remotePort: Int get() = assignedRemotePort

    fun start() {
        if (running) return
        running = true
        executor.execute { runLoop() }
    }

    fun stop() {
        running = false
        connected = false
        closeControl()
        // 关闭所有存活的转发连接
        synchronized(dataSockets) {
            dataSockets.toList().forEach { closeQuietly(it) }
            dataSockets.clear()
        }
    }

    private fun closeControl() {
        controlSocket?.let { closeQuietly(it) }
        controlSocket = null
    }

    // ==================== 控制连接主循环 ====================

    private fun runLoop() {
        var retryDelay = INITIAL_RETRY_MS
        while (running) {
            val ok = connectOnce()
            if (!running) break
            retryDelay = if (ok) INITIAL_RETRY_MS else (retryDelay * 2).coerceAtMost(MAX_RETRY_MS)
            log("${if (ok) "连接已断开" else "连接失败"}，${retryDelay / 1000} 秒后自动重连...")
            try {
                Thread.sleep(retryDelay)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** 单次控制连接流程。返回 true 表示成功建立且正常退出（断线），false 表示握手/解析失败 */
    private fun connectOnce(): Boolean {
        val socket = Socket()
        try {
            log("连接到 $serverHost:${BoreProtocol.CONTROL_PORT} ...")
            socket.connect(InetSocketAddress(serverHost, BoreProtocol.CONTROL_PORT), CONNECT_TIMEOUT_MS)
            socket.keepAlive = true
            socket.tcpNoDelay = true
            // 控制连接读超时：断线/stop 时不会永久阻塞在 read() 上
            socket.soTimeout = READ_TIMEOUT_MS
            controlSocket = socket

            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            if (!secret.isNullOrEmpty()) {
                BoreProtocol.authenticate(input, output, secret)
                log("认证完成")
            }
            sendMessage(output, BoreProtocol.hello(requestedRemotePort))
            log("已发送 Hello($requestedRemotePort)")

            val response = recvMessage(input)
                ?: return failWith("服务器关闭了连接", socket)
            when {
                response.startsWith("{\"Hello") -> {
                    assignedRemotePort = JSONObject(response).getInt("Hello")
                    connected = true
                    val publicUrl = "tcp://$serverHost:$assignedRemotePort"
                    val mcpUrl = "http://$serverHost:$assignedRemotePort/mcp"
                    log("分配到公网端口: $assignedRemotePort")
                    callback.onConnected(publicUrl, mcpUrl)
                    startHeartbeat()
                    runControlLoop(input, output)
                    connected = false
                    callback.onDisconnected()
                    return true
                }
                response.contains("\"Error\"") -> {
                    val msg = JSONObject(response).optString("Error", response)
                    return failWith("服务端错误: $msg", socket)
                }
                else -> return failWith("意外的响应: $response", socket)
            }
        } catch (e: IOException) {
            Log.e(TAG, "隧道异常", e)
            connected = false
            if (running) {
                error("隧道异常: ${e.message}")
                callback.onDisconnected()
            }
            closeQuietly(socket)
            return running
        } catch (e: Exception) {
            Log.e(TAG, "解析服务端消息失败", e)
            connected = false
            callback.onDisconnected()
            closeQuietly(socket)
            return false
        }
    }

    /** 已通过握手后的控制消息循环 */
    private fun runControlLoop(input: InputStream, output: OutputStream) {
        while (running) {
            val message = recvMessage(input) ?: break
            when {
                message == "\"Heartbeat\"" -> Log.d(TAG, "收到心跳")
                message.startsWith("{\"Connection") -> {
                    val connectionId = JSONObject(message).getString("Connection")
                    log("新连接请求: ${connectionId.take(8)}...")
                    handleDataConnection(connectionId)
                }
                message.contains("\"Error\"") -> {
                    error("服务端错误: ${JSONObject(message).optString("Error", message)}")
                    break
                }
                else -> Log.w(TAG, "未知消息: $message")
            }
        }
        // 循环因读失败/服务端断开而退出
        closeControl()
    }

    private fun failWith(message: String, socket: Socket): Boolean {
        connected = false
        error(message)
        callback.onDisconnected()
        closeQuietly(socket)
        return false
    }

    // ==================== 心跳 ====================

    private fun startHeartbeat() {
        heartbeatExecutor.execute {
            while (running && connected) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@execute
                }
                if (!running || !connected) return@execute
                try {
                    sendMessage(controlSocket?.getOutputStream() ?: return@execute, "\"Heartbeat\"")
                    Log.d(TAG, "发送心跳")
                } catch (e: Exception) {
                    Log.w(TAG, "心跳发送失败: ${e.message}")
                    // 心跳写失败说明控制连接已死，尽快触发重连
                    closeControl()
                    return@execute
                }
            }
        }
    }

    // ==================== 数据连接转发 ====================

    private fun handleDataConnection(connectionId: String) {
        executor.execute {
            val dataSocket = Socket()
            try {
                dataSocket.connect(InetSocketAddress(serverHost, BoreProtocol.CONTROL_PORT), CONNECT_TIMEOUT_MS)
                dataSocket.tcpNoDelay = true
                if (!secret.isNullOrEmpty()) {
                    BoreProtocol.authenticate(dataSocket.getInputStream(), dataSocket.getOutputStream(), secret)
                }
                sendMessage(dataSocket.getOutputStream(), BoreProtocol.accept(connectionId))

                val localSocket = Socket()
                try {
                    localSocket.connect(InetSocketAddress(LOCAL_HOST, localPort), CONNECT_TIMEOUT_MS)
                    localSocket.tcpNoDelay = true
                } catch (e: Exception) {
                    closeQuietly(localSocket)
                    throw IOException("连接本地端口 $localPort 失败: ${e.message}")
                }

                synchronized(dataSockets) { dataSockets.add(dataSocket); dataSockets.add(localSocket) }
                pipeBidirectional(dataSocket, localSocket)
            } catch (e: Exception) {
                Log.w(TAG, "数据连接 ${connectionId.take(8)} 失败: ${e.message}")
            } finally {
                synchronized(dataSockets) { dataSockets.remove(dataSocket) }
                closeQuietly(dataSocket)
            }
        }
    }

    /** 双向转发两个 socket，任一端关闭即关闭另一端 */
    private fun pipeBidirectional(remote: Socket, local: Socket) {
        val closed = AtomicBoolean(false)
        val closeBoth = {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(remote)
                closeQuietly(local)
            }
        }
        executor.execute {
            try {
                pipeStreams(remote.getInputStream(), local.getOutputStream())
            } catch (_: IOException) {
            }
            closeBoth()
        }
        try {
            pipeStreams(local.getInputStream(), remote.getOutputStream())
        } catch (_: IOException) {
        }
        closeBoth()
    }

    private fun pipeStreams(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            output.write(buffer, 0, n)
            output.flush()
        }
    }

    // ==================== 协议收发 ====================

    private fun recvMessage(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b != 0) {
                buffer.write(b)
                if (buffer.size() > BoreProtocol.MAX_FRAME_LENGTH) {
                    throw IOException("消息超过最大长度 ${BoreProtocol.MAX_FRAME_LENGTH}")
                }
            } else {
                return buffer.toString(StandardCharsets.UTF_8.name())
            }
        }
        return if (buffer.size() <= 0) null else buffer.toString(StandardCharsets.UTF_8.name())
    }

    private fun sendMessage(output: OutputStream, message: String) {
        val data = message.toByteArray(StandardCharsets.UTF_8)
        if (data.size > BoreProtocol.MAX_FRAME_LENGTH) {
            throw IOException("消息过长: ${data.size} > ${BoreProtocol.MAX_FRAME_LENGTH}")
        }
        synchronized(writeLock) {
            output.write(data)
            output.write(0)
            output.flush()
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        callback.onLog(message)
    }

    private fun error(message: String) {
        Log.e(TAG, message)
        callback.onError(message)
    }

    companion object {
        private const val TAG = "BoreClient"
        private const val LOCAL_HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 45_000
        private const val HEARTBEAT_INTERVAL_MS = 25_000L
        private const val INITIAL_RETRY_MS = 2000L
        private const val MAX_RETRY_MS = 30_000L

        private fun closeQuietly(socket: Socket?) {
            if (socket != null && !socket.isClosed) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }
    }
}

/** bore 协议静态部分：消息构造、帧编解码、HMAC-SHA256 认证 */
internal object BoreProtocol {
    const val CONTROL_PORT = 7835
    const val MAX_FRAME_LENGTH = 256

    fun hello(requestedRemotePort: Int): String = "{\"Hello\":$requestedRemotePort}"
    fun accept(connectionId: String): String = "{\"Accept\":\"$connectionId\"}"
    fun heartbeat(): String = "\"Heartbeat\""

    /**
     * 完成 Challenge/Authenticate 认证握手。
     * 服务器先发 `{"Challenge":"<uuid>"}`，客户端回 `{"Authenticate":"<hmac>"}`。
     */
    fun authenticate(input: InputStream, output: OutputStream, secret: String) {
        val challengeMessage = readFrame(input)
            ?: throw IOException("认证握手失败：服务器未发送 Challenge")
        if (!challengeMessage.contains("\"Challenge\"")) {
            throw IOException("期望 Challenge 消息但收到: $challengeMessage")
        }
        val challenge = JSONObject(challengeMessage).getString("Challenge")
        writeFrame(output, "{\"Authenticate\":\"${computeHmacAnswer(secret, challenge)}\"}")
    }

    private fun readFrame(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b != 0) {
                buffer.write(b)
                if (buffer.size() > MAX_FRAME_LENGTH) throw IOException("消息超过最大长度 $MAX_FRAME_LENGTH")
            } else {
                return buffer.toString(StandardCharsets.UTF_8.name())
            }
        }
        return if (buffer.size() <= 0) null else buffer.toString(StandardCharsets.UTF_8.name())
    }

    private fun writeFrame(output: OutputStream, message: String) {
        val data = message.toByteArray(StandardCharsets.UTF_8)
        if (data.size > MAX_FRAME_LENGTH) throw IOException("消息过长: ${data.size} > $MAX_FRAME_LENGTH")
        output.write(data)
        output.write(0)
        output.flush()
    }

    /** HMAC 应答：key = SHA-256(secret)，对 challenge UUID 的 16 字节做 HmacSHA256，输出 hex */
    fun computeHmacAnswer(secret: String, challenge: String): String {
        val key = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(StandardCharsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return bytesToHex(mac.doFinal(uuidToBytes(UUID.fromString(challenge))))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val bytes = ByteArray(16)
        var msb = uuid.mostSignificantBits
        var lsb = uuid.leastSignificantBits
        for (i in 7 downTo 0) {
            bytes[i] = (msb and 0xFF).toByte()
            bytes[i + 8] = (lsb and 0xFF).toByte()
            msb = msb ushr 8
            lsb = lsb ushr 8
        }
        return bytes
    }
}
