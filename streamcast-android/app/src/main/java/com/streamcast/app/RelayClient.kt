package com.streamcast.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * 与中继服务之间的 WebSocket 通道。
 * 发送端：发送 meta 信令 + 二进制视频帧；接收端：接收信令 + 二进制视频帧。
 */
class RelayClient(
    private val serverUrl: String,
    private val room: String,
    private val role: String,
    private val listener: Listener,
    private val token: String? = null,
    private val privateRoom: Boolean = false,
    private val title: String = ""
) {

    interface Listener {
        fun onConnecting()
        fun onConnected(room: String)
        fun onControl(type: String, json: JSONObject)
        fun onFrame(bytes: ByteArray)
        fun onClosed(reason: String)
        fun onFailure(message: String)
    }

    /** 关闭 Nagle 算法，避免小包被攒着等 ACK（音频帧只有几百字节，最坏会引入约 40ms 延迟） */
    private val noDelaySocketFactory = object : SocketFactory() {
        private val delegate = SocketFactory.getDefault()

        override fun createSocket(): Socket =
            delegate.createSocket().apply { tcpNoDelay = true }

        override fun createSocket(host: String, port: Int): Socket =
            delegate.createSocket(host, port).apply { tcpNoDelay = true }

        override fun createSocket(
            host: String, port: Int, localHost: InetAddress, localPort: Int
        ): Socket =
            delegate.createSocket(host, port, localHost, localPort).apply { tcpNoDelay = true }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate.createSocket(host, port).apply { tcpNoDelay = true }

        override fun createSocket(
            address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int
        ): Socket =
            delegate.createSocket(address, port, localAddress, localPort).apply { tcpNoDelay = true }
    }

    private val client = OkHttpClient.Builder()
        .socketFactory(noDelaySocketFactory)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    private val dropped = AtomicLong(0)

    /** 最大待发送队列（字节），超过则丢帧，避免内存膨胀与延迟堆积 */
    private val maxQueueBytes = 3L * 1024 * 1024

    /** 视频帧的硬性保护上限：正常拥塞交给 CongestionGate 处理，这里只防内存异常 */
    private val hardCapBytes = 8L * 1024 * 1024

    fun connect() {
        listener.onConnecting()
        val request = Request.Builder().url(serverUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val join = JSONObject()
                    .put("type", "join")
                    .put("room", room)
                    .put("role", role)
                    .put("name", "android-${role}")
                // 开播需要登录态；隐私模式让房间不出现在大厅
                if (!token.isNullOrEmpty()) join.put("token", token)
                if (role == "sender") {
                    join.put("private", privateRoom)
                    if (title.isNotBlank()) join.put("title", title)
                }
                webSocket.send(join.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                val type = json.optString("type")
                when (type) {
                    "joined" -> listener.onConnected(json.optString("room", room))
                    "error" -> listener.onFailure(json.optString("message", "服务端拒绝连接"))
                    else -> listener.onControl(type, json)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onFrame(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t.message ?: t.javaClass.simpleName)
            }
        })
    }

    /** 发送二进制视频帧；队列积压时丢弃返回 false */
    fun sendFrame(frame: ByteArray): Boolean {
        val ws = socket ?: return false
        if (ws.queueSize() > maxQueueBytes) {
            dropped.incrementAndGet()
            return false
        }
        return try {
            ws.send(frame.toByteString())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 发送音频帧（ADTS 封装 AAC）。
     * 前置一个 0x02 标记字节与视频帧区分：视频帧始终是裸 JPEG（0xFFD8 开头），
     * 二者不会混淆，且视频通道格式保持不变，兼容既有接收端。
     */
    fun sendAudioFrame(adts: ByteArray): Boolean {
        val ws = socket ?: return false
        if (ws.queueSize() > maxQueueBytes) return false
        val msg = ByteArray(adts.size + 1)
        msg[0] = AppConfig.FRAME_AUDIO
        System.arraycopy(adts, 0, msg, 1, adts.size)
        return try {
            ws.send(msg.toByteString())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 发送 H.264 编码帧（Annex-B 访问单元）。
     * 前置 0x03 标记与视频/音频区分。
     */
    fun sendVideoH264(au: ByteArray): Boolean {
        val ws = socket ?: return false
        if (ws.queueSize() > hardCapBytes) return false
        val msg = ByteArray(au.size + 1)
        msg[0] = AppConfig.FRAME_VIDEO_H264
        System.arraycopy(au, 0, msg, 1, au.size)
        return try {
            ws.send(msg.toByteString())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通知接收端「我已丢弃积压，请跳到下一个关键帧」。
     * 接收端收到后应立即清空解码器，并只接受带 SPS/IDR 的帧，
     * 从而把画面拉到直播边缘，而不是继续消费旧数据。
     */
    fun sendResync(): Boolean {
        val ws = socket ?: return false
        return try {
            ws.send(byteArrayOf(AppConfig.FRAME_RESYNC).toByteString())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 下发 H.264 编解码配置：0x04 + 宽(2字节) + 高(2字节) + csd-0。
     * 接收端据此初始化 MediaCodec，中途加入也能立刻配置好解码器。
     */
    fun sendCodecConfig(csd: ByteArray, width: Int, height: Int): Boolean {
        val ws = socket ?: return false
        val msg = ByteArray(5 + csd.size)
        msg[0] = AppConfig.FRAME_CODEC_CONFIG
        msg[1] = ((width shr 8) and 0xFF).toByte()
        msg[2] = (width and 0xFF).toByte()
        msg[3] = ((height shr 8) and 0xFF).toByte()
        msg[4] = (height and 0xFF).toByte()
        System.arraycopy(csd, 0, msg, 5, csd.size)
        return try {
            ws.send(msg.toByteString())
        } catch (e: Exception) {
            false
        }
    }

    fun sendMeta(width: Int, height: Int, fps: Int, quality: Int): Boolean {
        val ws = socket ?: return false
        val json = JSONObject()
            .put("type", "meta")
            .put("width", width)
            .put("height", height)
            .put("fps", fps)
            .put("quality", quality)
        return try {
            ws.send(json.toString())
        } catch (e: Exception) {
            false
        }
    }

    fun requestKeyframe() {
        val ws = socket ?: return
        try {
            ws.send(JSONObject().put("type", "request-keyframe").toString())
        } catch (e: Exception) {
            // ignore
        }
    }

    fun droppedFrames(): Long = dropped.get()

    /** 当前待发送字节数；持续积压即链路拥塞信号 */
    fun pendingBytes(): Long = socket?.queueSize() ?: 0L

    fun close() {
        try {
            socket?.close(1000, "bye")
        } catch (e: Exception) {
            // ignore
        }
        socket = null
    }
}
