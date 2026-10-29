package kmp.bootstrap.appkit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Ngăn xếp điều hướng KHÔNG dính Compose (nên test được trên JVM), dùng cho mọi app. Route của app là một sealed interface bất kỳ:
 *
 *     sealed interface Route { data object Home : Route; data class Detail(val id: String) : Route }
 *     val nav = Navigator<Route>(Route.Home)
 *     nav.push(Route.Detail("42"))     // vào màn con
 *     nav.back()                        // lùi; false nếu đã ở gốc (để hệ điều hành thoát app)
 *     nav.reset(Route.Home)             // đổi tab gốc / sau đăng nhập: xoá cả ngăn xếp
 *     nav.current.collect { … }         // StateFlow — Compose đọc bằng collectAsState (xem NavHost ở appkit-ui)
 */
class Navigator<R : Any>(start: R) {
    private val _stack = MutableStateFlow(listOf(start))
    /** Cả ngăn xếp, gốc ở đầu. */
    val stack: StateFlow<List<R>> = _stack.asStateFlow()
    val current: R get() = _stack.value.last()
    val canGoBack: Boolean get() = _stack.value.size > 1

    /** Vào màn con. Đang ở đúng route đó rồi thì không xếp thêm (bấm đúp không mở hai lần). */
    fun push(route: R) { if (current != route) _stack.value = _stack.value + route }

    /** Thay màn hiện tại bằng [route] (không để lại trong lịch sử). */
    fun replace(route: R) { _stack.value = _stack.value.dropLast(1) + route }

    /** Xoá cả ngăn xếp, chỉ còn [route]: đổi tab gốc, hoặc sang luồng khác (đăng nhập xong). */
    fun reset(route: R) { _stack.value = listOf(route) }

    /** Lùi một màn. Trả false khi đã ở gốc — khi đó để hệ điều hành xử lý nút lùi (thoát app). */
    fun back(): Boolean {
        if (!canGoBack) return false
        _stack.value = _stack.value.dropLast(1)
        return true
    }

    /** Lùi về route gần nhất thoả [predicate] (giữ route đó). Không có thì không đổi gì và trả false. */
    fun popTo(predicate: (R) -> Boolean): Boolean {
        val i = _stack.value.indexOfLast(predicate)
        if (i < 0) return false
        _stack.value = _stack.value.take(i + 1)
        return true
    }
}

/**
 * Đổi liên kết (deep link, link chia sẻ) thành route, theo mẫu đường dẫn có tham số `{ten}`:
 *
 *     val links = DeepLinks<Route> {
 *         route("/s/{token}") { Route.PublicActivity(it["token"]!!) }
 *         route("/activities/{id}") { Route.Detail(it["id"]!!) }
 *     }
 *     links.resolve("https://app.example.com/s/abc123?x=1")   // → Route.PublicActivity("abc123")
 *
 * Bỏ qua tên miền của link web (http/https), query và dấu `/` thừa (link `myapp://s/abc` coi `s/abc` là đường dẫn); mẫu khớp theo từng đoạn, nên `/s/{token}` không khớp `/s/a/b`. Không khớp mẫu nào thì null.
 */
class DeepLinks<R : Any>(build: Builder<R>.() -> Unit) {
    class Builder<R : Any> {
        internal val rules = mutableListOf<Pair<List<String>, (Map<String, String>) -> R?>>()
        fun route(pattern: String, make: (Map<String, String>) -> R?) { rules += segments(pattern) to make }
    }

    private val rules = Builder<R>().apply(build).rules

    fun resolve(link: String): R? {
        val parts = segments(pathOf(link))
        for ((pattern, make) in rules) {
            if (pattern.size != parts.size) continue
            val params = mutableMapOf<String, String>()
            val ok = pattern.indices.all { i ->
                val p = pattern[i]
                if (p.startsWith("{") && p.endsWith("}")) { params[p.substring(1, p.length - 1)] = parts[i]; true } else p == parts[i]
            }
            if (ok) make(params)?.let { return it }
        }
        return null
    }

    private companion object {
        fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }
        fun pathOf(link: String): String {
            val noQuery = link.substringBefore('#').substringBefore('?')
            if ("://" !in noQuery) return "/" + noQuery
            val scheme = noQuery.substringBefore("://").lowercase()
            val rest = noQuery.substringAfter("://")
            // http(s)://tên-miền/đường/dẫn → bỏ tên miền;  myapp://đường/dẫn → "tên miền" của scheme riêng chính là đoạn đầu của đường dẫn, giữ nguyên.
            return "/" + if (scheme == "http" || scheme == "https") rest.substringAfter('/', "") else rest
        }
    }
}
