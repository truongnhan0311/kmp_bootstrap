package kmp.bootstrap.appkit.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * VÍ DỤ CHẠY ĐƯỢC của appkit-mqtt (với broker giả trong test). Các đoạn giữa `// region` và `// endregion` được chép vào docs/mqtt.md.
 */
class MqttExamples {
    @Test fun device() = runBlocking {
        val broker = FakeBroker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connector = TcpConnector("127.0.0.1", broker.port)   // trong app: WebSocketConnector("wss://mq.example.com/mqtt")
        suspend fun devicePassword() = "pw"                       // trong app: MqttDeviceAuth.password(seckey, devid) của appkit-iot
        withTimeout(10.seconds) {
            // region: mqtt-connect
            val client = MqttClient(
                MqttOptions(
                    clientId = "dev123", username = "dev123",
                    password = { devicePassword() },                // gọi lại MỖI lần nối: mật khẩu có hạn vẫn đúng sau khi rớt mạng
                    will = MqttWill("cam/presence/dev123", """{"online":false}""".encodeToByteArray()),   // broker phát khi rớt mạng
                ),
                connector,
            )
            client.start()                                          // tự nối lại: 1s, 2s, 4s… tối đa 30s
            val listening = launch {
                client.subscribe("cam/device/in/dev123").collect { msg ->   // đang thu thì đang đăng ký; nối lại tự đăng ký lại
                    println("lệnh: ${msg.text}")
                }
            }
            client.awaitConnected()
            client.publish("cam/presence/dev123", """{"online":true}""", qos = 1, retain = true)   // treo tới khi broker xác nhận
            // endregion
            // region: mqtt-state
            val state = client.state.value
            val text = when (state) {
                MqttState.Connected -> "đã nối"
                is MqttState.Waiting -> if (state.lastError.badCredentials) "sai mật khẩu" else "nối lại sau ${state.retryIn}"
                is MqttState.Connecting, MqttState.Idle -> "đang nối"
                MqttState.Stopped -> "đã dừng"
            }
            client.stop()                                           // DISCONNECT: broker KHÔNG phát Last Will
            // endregion
            listening.cancel()
            assertEquals("đã nối", text)
            while (broker.publishes().none { it.topic == "cam/presence/dev123" }) delay(10)
            assertEquals(MqttState.Stopped, client.state.first())
        }
        scope.cancel(); broker.close()
    }
}
