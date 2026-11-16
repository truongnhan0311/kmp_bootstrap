package kmp.bootstrap.appkit.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Thử với broker THẬT (mặc định bỏ qua). Đặt biến môi trường rồi chạy:
 *   MQTT_TEST_WS=ws://127.0.0.1:8083/mqtt MQTT_TEST_TCP=127.0.0.1:1883 MQTT_TEST_USER=… MQTT_TEST_PASS=… \
 *     ./gradlew -PdesktopOnly=true :appkit-mqtt:desktopTest --tests '*RealBrokerTest*'
 * Với Recam: chạy `recam --role all` có RECAM_MQTT_WS_LISTEN, dùng tài khoản nội bộ (được nghe/ghi mọi chủ đề).
 */
class RealBrokerTest {
    private val user = System.getenv("MQTT_TEST_USER")
    private val pass = System.getenv("MQTT_TEST_PASS")

    private fun roundTrip(label: String, connector: () -> MqttConnector) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(15.seconds) {
                val opts = { id: String -> MqttOptions("$label-$id-${System.nanoTime()}", username = user, password = { pass }, keepAlive = 5.seconds) }
                val sub = MqttClient(opts("sub"), connector(), scope).apply { start() }
                val pub = MqttClient(opts("pub"), connector(), scope).apply { start() }
                sub.awaitConnected(); pub.awaitConnected()
                val topic = "kit-test/$label/${System.nanoTime()}"
                val got = scope.async { sub.subscribe(topic).take(2).toList() }
                delay(300)                                         // broker thật: chờ SUBACK
                pub.publish(topic, "q0", qos = 0)
                pub.publish(topic, "q1", qos = 1)
                assertEquals(listOf("q0", "q1"), got.await().map { it.text })
                delay(6_000)                                       // > keepAlive: ping thật phải giữ kết nối
                assertEquals(MqttState.Connected, sub.state.value)
                pub.stop(); sub.stop()
            }
        } finally { scope.cancel() }
    }

    @Test fun webSocket() {
        val url = System.getenv("MQTT_TEST_WS") ?: return println("RealBrokerTest.webSocket bỏ qua: chưa đặt MQTT_TEST_WS")
        roundTrip("ws") { WebSocketConnector(url) }
    }

    @Test fun tcp() {
        val hp = System.getenv("MQTT_TEST_TCP") ?: return println("RealBrokerTest.tcp bỏ qua: chưa đặt MQTT_TEST_TCP")
        roundTrip("tcp") { TcpConnector(hp.substringBefore(':'), hp.substringAfter(':').toInt()) }
    }
}
