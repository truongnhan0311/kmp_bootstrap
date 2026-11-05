package kmp.bootstrap.appkit.mqtt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/*
 * Đường truyền byte cho MqttClient. Kit có sẵn MQTT qua WebSocket (`wss://…/mqtt`, subprotocol "mqtt"): chạy được trên
 * Android (OkHttp), iOS (Darwin) và desktop (CIO) bằng cùng một code, đi qua cổng 443 nên ít bị tường lửa chặn.
 * Cần đường khác (TCP 8883, hoặc giả lập trong test) thì tự cài MqttConnector.
 */

interface MqttConnection {
    suspend fun send(bytes: ByteArray)
    /** Mảng byte kế tiếp (có thể là một phần của gói, hoặc nhiều gói); null khi kết nối đã đóng. */
    suspend fun receive(): ByteArray?
    suspend fun close()
}

fun interface MqttConnector {
    suspend fun open(): MqttConnection
}

/** HttpClient đã cài plugin WebSockets; engine lấy theo nền tảng (OkHttp / Darwin / CIO). */
fun mqttHttpClient(): HttpClient = HttpClient { install(WebSockets) }

/** [url] được hỏi lại ở mỗi lần nối, nên đổi địa chỉ broker (chuyển shard) chỉ cần đổi giá trị nó trả về. */
class WebSocketConnector(
    private val client: HttpClient,
    private val url: suspend () -> String,
) : MqttConnector {
    override suspend fun open(): MqttConnection {
        val session = client.webSocketSession(url()) { header(HttpHeaders.SecWebSocketProtocol, "mqtt") }
        return object : MqttConnection {
            override suspend fun send(bytes: ByteArray) = session.send(Frame.Binary(true, bytes))
            override suspend fun receive(): ByteArray? {
                while (true) {
                    val frame = try { session.incoming.receive() } catch (_: ClosedReceiveChannelException) { return null }
                    when (frame) {
                        is Frame.Binary -> return frame.data
                        is Frame.Close -> return null
                        else -> Unit   // Ping/Pong do Ktor lo; Text không thuộc MQTT
                    }
                }
            }
            override suspend fun close() = session.close()
        }
    }
}
