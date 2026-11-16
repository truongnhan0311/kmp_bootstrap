package kmp.bootstrap.appkit.mqtt

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * MQTT qua WebSocket: một máy chủ WebSocket (Ktor) nối cầu từng khung nhị phân sang broker giả qua TCP, giống cách
 * mochi-mqtt phục vụ cổng WebSocket. Kiểm client gửi subprotocol `mqtt`, gói bị cắt/gộp qua khung vẫn ghép đúng.
 */
class WebSocketTest {
    private val broker = FakeBroker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val protocols = java.util.concurrent.CopyOnWriteArrayList<String?>()
    private val port = ServerSocket(0).use { it.localPort }
    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = port, host = "127.0.0.1") {
        install(WebSockets)
        routing {
            webSocket("/mqtt") {
                protocols += call.request.headers["Sec-WebSocket-Protocol"]
                val tcp = withContext(Dispatchers.IO) { Socket("127.0.0.1", broker.port) }
                val pump = launch(Dispatchers.IO) {
                    val buf = ByteArray(7)                                      // mẩu nhỏ: gói bị cắt qua nhiều khung
                    val input = tcp.getInputStream()
                    while (true) { val n = input.read(buf); if (n < 0) break; send(Frame.Binary(true, buf.copyOf(n))) }
                }
                try {
                    for (frame in incoming) if (frame is Frame.Binary) withContext(Dispatchers.IO) { tcp.getOutputStream().write(frame.readBytes()) }
                } finally { pump.cancel(); tcp.close() }
            }
        }
    }.start(wait = false)

    @AfterTest fun tearDown() { scope.cancel(); server.stop(100, 500); broker.close() }

    @Test fun mqttOverWebSocketRoundTrip() = runBlocking {
        withTimeout(10.seconds) {
            val opts = { id: String -> MqttOptions(id, backoffMin = 50.milliseconds, backoffMax = 200.milliseconds) }
            val a = MqttClient(opts("ws-a"), WebSocketConnector("ws://127.0.0.1:$port/mqtt"), scope).apply { start() }
            val b = MqttClient(opts("ws-b"), WebSocketConnector("ws://127.0.0.1:$port/mqtt"), scope).apply { start() }
            a.awaitConnected(); b.awaitConnected()
            val got = scope.async { b.subscribe("cam/session/s1").take(1).toList() }
            while (broker.clients.none { it.clientId == "ws-b" && it.subs.isNotEmpty() }) delay(10)
            val big = "x".repeat(20_000)                                        // độ dài 3 byte, nhiều khung
            a.publish("cam/session/s1", big, qos = 1)
            assertEquals(big, got.await().single().text)
            assertEquals(listOf("mqtt", "mqtt"), protocols.toList())
            a.stop(); b.stop()
        }
    }

    @Test fun onlyWebSocketUrlsAreAccepted() {
        assertFailsWith<IllegalArgumentException> { WebSocketConnector("mqtt://127.0.0.1:1883") }
    }
}
