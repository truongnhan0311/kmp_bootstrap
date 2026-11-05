package kmp.bootstrap.appkit.mqtt

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Chạy MqttClient + WebSocketConnector qua WebSocket thật (localhost) tới một broker tối giản viết bằng MqttCodec. */
class WebSocketConnectorTest {
    @Test fun connectSubscribeAndReceiveOverRealWebSocket() = runBlocking {
        val seen = mutableListOf<MqttPacket>()
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/mqtt", protocol = "mqtt") {
                    val reader = MqttFrameReader()
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        for (p in reader.feed(frame.readBytes())) {
                            synchronized(seen) { seen += p }
                            when (p) {
                                is MqttPacket.Connect -> send(Frame.Binary(true, MqttCodec.encode(MqttPacket.ConnAck(false, 0))))
                                is MqttPacket.Subscribe -> {
                                    send(Frame.Binary(true, MqttCodec.encode(MqttPacket.SubAck(p.packetId, listOf(1)))))
                                    send(Frame.Binary(true, MqttCodec.encode(MqttPacket.Publish(p.topics[0].first, "hello".encodeToByteArray()))))
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mqtt = MqttClient(MqttOptions("ws-test", credentials = { MqttCredentials("u", "p") }),
                WebSocketConnector(mqttHttpClient()) { "ws://127.0.0.1:$port/mqtt" }, scope)
            withTimeout(10_000) {
                val msg = async { mqtt.messages.first() }
                mqtt.start()
                mqtt.subscribe("in/ws-test")
                assertEquals("hello", msg.await().payload.decodeToString())
                mqtt.stop()
            }
            val connect = synchronized(seen) { seen.first() } as MqttPacket.Connect
            assertEquals("ws-test", connect.clientId)
            assertEquals("u", connect.username)
        } finally {
            scope.cancel()
            server.stop(100, 500)
        }
    }
}
