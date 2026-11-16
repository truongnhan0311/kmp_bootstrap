package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/*
 * Máy chủ STUN/TURN cho một cuộc gọi. Thông tin TURN kiểu "TURN REST" (RFC 7635 / coturn use-auth-secret):
 *   username   = "<hết hạn, giây Unix>:<định danh>"     credential = base64(HMAC-SHA1(secret, username))
 * Hạn nằm ngay trong username nên app đọc được mà không cần server báo riêng. Username có dấu ':' nên PHẢI đưa cho thư viện
 * ICE thành trường riêng, không ghép vào URL kiểu `turn:user:pass@host` (thư viện sẽ cắt sai).
 */
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
    /** Hạn do server báo (mili-giây Unix). Không có thì đọc từ username kiểu TURN REST. */
    val expiresAtMillis: Long? = null,
) {
    init {
        require(urls.isNotEmpty()) { "ice server needs at least one url" }
        urls.forEach { u ->
            require(u.startsWith("stun:") || u.startsWith("turn:") || u.startsWith("turns:")) { "ice url must start with stun:, turn: or turns: ($u)" }
            require('@' !in u) { "credentials must not be embedded in the url; use username/credential" }
        }
        if (urls.any { it.startsWith("turn") }) require(!username.isNullOrEmpty() && !credential.isNullOrEmpty()) { "turn server needs username and credential" }
    }

    /** Hạn dùng (mili-giây Unix): của server, hoặc đọc từ username TURN REST; `null` nếu không hết hạn (STUN). */
    val expiresAt: Long? get() = expiresAtMillis ?: username?.let { turnRestExpiry(it) }

    val isTurn: Boolean get() = urls.any { it.startsWith("turn") }

    companion object {
        /** "1763366400:session-1" → 1763366400000. Username không theo dạng TURN REST → `null`. */
        fun turnRestExpiry(username: String): Long? = username.substringBefore(':', "").toLongOrNull()?.times(1000)
    }
}

class IceConfig(val servers: List<IceServer>) {
    /** Hạn sớm nhất trong các máy chủ (mili-giây Unix), `null` nếu không có hạn. */
    val expiresAt: Long? get() = servers.mapNotNull { it.expiresAt }.minOrNull()

    val hasRelay: Boolean get() = servers.any { it.isTurn }

    /** Còn dùng được ít nhất [margin] nữa không. Cuộc gọi dài phải xin lại TRƯỚC khi hết hạn, vì relay kiểm lại mỗi lần làm mới allocation. */
    fun isFresh(nowMillis: Long = Clock.System.now().toEpochMilliseconds(), margin: Duration = 1.hours): Boolean =
        expiresAt?.let { nowMillis + margin.inWholeMilliseconds < it } ?: true

    companion object {
        /** Gộp nhiều danh sách (vd STUN chung + relay UDP + relay TCP/TLS), bỏ trùng, giữ thứ tự. */
        fun of(vararg lists: List<IceServer>) = IceConfig(lists.flatMap { it }.distinct())
    }
}

/**
 * Giữ một [IceConfig] và chỉ xin lại khi gần hết hạn (hoặc sau [invalidate]). Nhiều nơi gọi [get] cùng lúc chỉ xin một lần.
 * Dùng trước khi tạo cuộc gọi và trước mỗi lần ICE restart.
 */
class IceConfigSource(
    private val fetch: suspend () -> IceConfig,
    private val margin: Duration = 1.hours,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private var cached: IceConfig? = null

    suspend fun get(): IceConfig = lock.withLock {
        cached?.takeIf { it.isFresh(nowMillis(), margin) } ?: fetch().also { cached = it }
    }

    suspend fun invalidate() = lock.withLock { cached = null }
}
