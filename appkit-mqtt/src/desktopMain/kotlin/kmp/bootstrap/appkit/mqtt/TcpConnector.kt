package kmp.bootstrap.appkit.mqtt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket

/** MQTT qua TCP thường (không TLS) — chỉ có trên JVM desktop, dùng cho test và công cụ. App điện thoại dùng [WebSocketConnector]. */
class TcpConnector(private val host: String, private val port: Int, private val connectTimeoutMillis: Int = 10_000) : MqttConnector {
    override suspend fun connect(): MqttTransport = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        TcpTransport(s)
    }
}

private class TcpTransport(private val socket: Socket) : MqttTransport {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val buf = ByteArray(8192)

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) { output.write(bytes); output.flush() }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        val n = input.read(buf)
        if (n < 0) throw EOFException("connection closed by broker")
        buf.copyOf(n)
    }

    override suspend fun close() = withContext(Dispatchers.IO) { runCatching { socket.close() }; Unit }
}
