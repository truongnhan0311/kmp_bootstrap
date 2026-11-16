package kmp.bootstrap.appkit.mqtt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes

/** Một kết nối đã mở tới broker: gửi byte, nhận từng mẩu byte (không nhất thiết trọn gói). Hỏng hay bị đóng → ném lỗi. */
interface MqttTransport {
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray
    suspend fun close()
}

/** Mở một kết nối mới; client gọi lại mỗi lần nối lại. */
fun interface MqttConnector {
    suspend fun connect(): MqttTransport
}

/**
 * MQTT qua WebSocket (`ws://` hoặc `wss://`), subprotocol `mqtt` như chuẩn yêu cầu. Chạy trên mọi nền tảng có engine Ktor
 * (desktop CIO, Android OkHttp, iOS Darwin); TLS do engine lo. Cổng 443 ít bị mạng công ty/khách sạn chặn hơn 1883/8883.
 * Truyền [client] riêng nếu muốn đặt engine, proxy hay chứng chỉ; nó phải cài plugin [WebSockets].
 */
class WebSocketConnector(
    private val url: String,
    private val client: HttpClient = HttpClient { install(WebSockets) },
) : MqttConnector {
    init { require(url.startsWith("ws://") || url.startsWith("wss://")) { "url must start with ws:// or wss://" } }

    override suspend fun connect(): MqttTransport {
        val session = client.webSocketSession(url) { header("Sec-WebSocket-Protocol", "mqtt") }
        return WebSocketTransport(session)
    }
}

private class WebSocketTransport(private val session: DefaultClientWebSocketSession) : MqttTransport {
    override suspend fun send(bytes: ByteArray) = session.send(Frame.Binary(true, bytes))

    override suspend fun receive(): ByteArray {
        while (true) {
            when (val frame = session.incoming.receive()) {   // kênh đóng → ClosedReceiveChannelException
                is Frame.Binary -> return frame.readBytes()
                is Frame.Close -> throw MqttProtocolException("websocket closed by server")
                is Frame.Text -> throw MqttProtocolException("text frame on an MQTT websocket")
                else -> Unit   // ping/pong do Ktor xử lý
            }
        }
    }

    override suspend fun close() { runCatching { session.close() } }
}
