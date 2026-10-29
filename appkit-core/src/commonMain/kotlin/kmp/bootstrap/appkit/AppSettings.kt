package kmp.bootstrap.appkit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lựa chọn giao diện của người dùng: theo máy, sáng, hoặc tối. */
enum class ThemeMode { System, Light, Dark }

/*
 * Cài đặt người dùng được NHỚ qua các lần mở app: giao diện sáng/tối/theo máy và ngôn ngữ.
 *
 *     val settings = AppSettings(AppKit.storage.prefs)          // đọc lại lựa chọn đã lưu, và đặt Language.current
 *     settings.setTheme(ThemeMode.Dark)                           // lưu + phát cho UI
 *     settings.setLanguage("vi")                                  // lưu + đổi Language.current
 *     settings.theme.collect { … }                                // StateFlow<ThemeMode>
 *     settings.applyAccount(theme = "dark", language = "en")      // sau đăng nhập: lựa chọn đã lưu ở tài khoản thắng máy cục bộ
 *
 * Quy tắc "lựa chọn gần nhất của người dùng thắng": chọn trên máy khi chưa lên được tài khoản thì lần đăng nhập sau KHÔNG bị tài khoản ghi đè
 * (xem [unsyncedChanges]); app đẩy lên tài khoản rồi gọi [markSynced].
 */
class AppSettings(private val prefs: KeyValueStore) {
    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    private val _language = MutableStateFlow("")
    /** Ngôn ngữ đã lưu ("" = theo máy). Ngôn ngữ ĐANG DÙNG là [Language.current]. */
    val language: StateFlow<String> = _language.asStateFlow()

    init {
        prefs.get(LANG_KEY)?.takeIf { it.isNotBlank() }?.let { _language.value = it; Language.current = it }
    }

    fun setTheme(mode: ThemeMode) { _theme.value = mode; prefs.put(THEME_KEY, mode.name.lowercase()); markUnsynced("theme") }

    /** Đặt ngôn ngữ ("vi", "en"…); chuỗi rỗng = quay về theo máy. */
    fun setLanguage(code: String) {
        _language.value = code; prefs.put(LANG_KEY, code.ifBlank { null }); markUnsynced("language")
        if (code.isBlank()) Language.reset() else Language.current = code
    }

    /** Thay đổi trên máy mà tài khoản chưa biết ("theme", "language"). Rỗng = đã đồng bộ. */
    val unsyncedChanges: Set<String> get() = prefs.get(UNSYNCED_KEY)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /** Báo đã đẩy các lựa chọn lên tài khoản thành công. */
    fun markSynced() { prefs.put(UNSYNCED_KEY, null) }

    /**
     * Áp lựa chọn lưu ở tài khoản (null = tài khoản chưa có). Trường nào máy đã đổi mà chưa đẩy lên ([unsyncedChanges]) thì GIỮ lựa chọn của máy.
     * Trả về các trường máy đang thắng, để app đẩy chúng lên tài khoản.
     */
    fun applyAccount(theme: String?, language: String?): Set<String> {
        val local = unsyncedChanges
        if ("theme" !in local && theme != null) ThemeMode.entries.firstOrNull { it.name.equals(theme, true) }?.let { _theme.value = it; prefs.put(THEME_KEY, it.name.lowercase()) }
        if ("language" !in local && language != null) { _language.value = language; prefs.put(LANG_KEY, language); Language.current = language }
        return local
    }

    private fun markUnsynced(field: String) { prefs.put(UNSYNCED_KEY, (unsyncedChanges + field).joinToString(",")) }
    private fun readTheme() = ThemeMode.entries.firstOrNull { it.name.equals(prefs.get(THEME_KEY), true) } ?: ThemeMode.System

    private companion object { const val THEME_KEY = "settings.theme"; const val LANG_KEY = "settings.language"; const val UNSYNCED_KEY = "settings.unsynced" }
}
