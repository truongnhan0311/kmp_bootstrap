package kmp.bootstrap.appkit

/*
 * Chữ hiện cho NGƯỜI DÙNG do kit sinh ra (lỗi mạng, lỗi định dạng, giải mã thất bại…) theo ngôn ngữ:
 *   1. ngôn ngữ app đặt:        Language.current = "vi"
 *   2. nếu chưa đặt → ngôn ngữ hệ thống của máy (Android/iOS/desktop)
 *   3. nếu chưa có bản dịch cho ngôn ngữ đó → tiếng Anh ("en"), luôn đầy đủ
 *
 * App có chuỗi riêng thì thêm/ghi đè:  Language.register("fr", mapOf(Msg.Timeout to "Délai dépassé"))
 * Chỉ chuỗi hướng tới NGƯỜI DÙNG được dịch. Dòng log và thông báo cho lập trình viên (require/check) cố định bằng tiếng Anh: log do người
 * phát triển/hỗ trợ đọc và grep, nên cùng một sự kiện phải ra cùng một câu trên mọi máy, bất kể ngôn ngữ người dùng.
 */
enum class Msg {
    NetworkUnreachable, Timeout, Unauthorized, Forbidden, NotFound, Conflict, Invalid, RateLimited, Server, Parse, Other,
    BodyEncodeFailed, BodyNotObject,
    DecryptFailed, BadFormat,
    NotificationChannel,
}

/** Mã ngôn ngữ ISO 639-1 viết thường của máy ("vi", "en", "zh"…). Nền tảng cài; Language tự chuẩn hoá và rơi về "en". */
expect fun systemLanguage(): String

object Language {
    private fun builtIn() = mutableMapOf<String, Map<Msg, String>>("en" to EN, "vi" to VI, "zh" to ZH, "es" to ES)
    private var tables = builtIn()
    private var override: String? = null

    /** Ngôn ngữ đang dùng. Chưa đặt thì theo máy; đặt `null` để quay lại theo máy. */
    var current: String
        get() = override ?: normalise(systemLanguage())
        set(value) { override = value.takeIf { it.isNotBlank() }?.let(::normalise) }

    /** Thêm hoặc ghi đè bản dịch (gộp với bảng sẵn có; khoá thiếu sẽ rơi về tiếng Anh). */
    fun register(language: String, messages: Map<Msg, String>) {
        val key = normalise(language)
        tables[key] = (tables[key] ?: emptyMap()) + messages
    }

    fun text(msg: Msg): String = tables[current]?.get(msg) ?: EN.getValue(msg)

    /** "vi-VN" → "vi", "zh_Hans_CN" → "zh", "EN" → "en". */
    fun normalise(raw: String): String = raw.trim().lowercase().split('-', '_').firstOrNull().orEmpty().ifEmpty { "en" }

    /** Quay về theo ngôn ngữ hệ thống và bảng dịch gốc (dùng trong test, hoặc khi người dùng chọn "theo máy"). */
    fun reset() { override = null; tables = builtIn() }
}

private val EN = mapOf(
    Msg.NetworkUnreachable to "Can't reach the server. Check your connection.",
    Msg.Timeout to "The server took too long to respond.",
    Msg.Unauthorized to "Your session is no longer valid. Please sign in again.",
    Msg.Forbidden to "You don't have permission to do this.",
    Msg.NotFound to "Not found.",
    Msg.Conflict to "This conflicts with the current state. Reload and try again.",
    Msg.Invalid to "The data was rejected. Please check it and try again.",
    Msg.RateLimited to "Too many requests. Please wait a moment.",
    Msg.Server to "The server had a problem. Please try again later.",
    Msg.Parse to "The server's response was not in the expected format.",
    Msg.Other to "Something went wrong.",
    Msg.BodyEncodeFailed to "Couldn't convert the data to send into JSON.",
    Msg.BodyNotObject to "The request body must be a JSON object.",
    Msg.DecryptFailed to "Decryption failed: wrong key or the data was altered.",
    Msg.BadFormat to "The data is not in a valid format or version.",
    Msg.NotificationChannel to "Notifications",
)

private val VI = mapOf(
    Msg.NetworkUnreachable to "Không kết nối được máy chủ. Kiểm tra mạng của bạn.",
    Msg.Timeout to "Máy chủ phản hồi quá lâu.",
    Msg.Unauthorized to "Phiên đăng nhập không còn hợp lệ. Vui lòng đăng nhập lại.",
    Msg.Forbidden to "Bạn không có quyền thực hiện việc này.",
    Msg.NotFound to "Không tìm thấy.",
    Msg.Conflict to "Xung đột với trạng thái hiện tại. Hãy tải lại rồi thử lại.",
    Msg.Invalid to "Dữ liệu bị từ chối. Hãy kiểm tra rồi thử lại.",
    Msg.RateLimited to "Gọi quá nhiều lần. Vui lòng chờ một lát.",
    Msg.Server to "Máy chủ gặp sự cố. Vui lòng thử lại sau.",
    Msg.Parse to "Phản hồi của máy chủ không đúng định dạng.",
    Msg.Other to "Đã có lỗi xảy ra.",
    Msg.BodyEncodeFailed to "Không đổi được dữ liệu gửi đi sang JSON.",
    Msg.BodyNotObject to "Body phải là một đối tượng JSON.",
    Msg.DecryptFailed to "Giải mã thất bại: sai khoá hoặc dữ liệu bị sửa.",
    Msg.BadFormat to "Dữ liệu không đúng định dạng hoặc phiên bản.",
    Msg.NotificationChannel to "Thông báo",
)

private val ES = mapOf(
    Msg.NetworkUnreachable to "No se puede conectar con el servidor. Revisa tu conexión.",
    Msg.Timeout to "El servidor tardó demasiado en responder.",
    Msg.Unauthorized to "Tu sesión ya no es válida. Inicia sesión de nuevo.",
    Msg.Forbidden to "No tienes permiso para hacer esto.",
    Msg.NotFound to "No encontrado.",
    Msg.Conflict to "Entra en conflicto con el estado actual. Recarga e inténtalo de nuevo.",
    Msg.Invalid to "Los datos fueron rechazados. Revísalos e inténtalo de nuevo.",
    Msg.RateLimited to "Demasiadas solicitudes. Espera un momento.",
    Msg.Server to "El servidor tuvo un problema. Inténtalo más tarde.",
    Msg.Parse to "La respuesta del servidor no tiene el formato esperado.",
    Msg.Other to "Algo salió mal.",
    Msg.BodyEncodeFailed to "No se pudieron convertir los datos a JSON.",
    Msg.BodyNotObject to "El cuerpo de la solicitud debe ser un objeto JSON.",
    Msg.DecryptFailed to "Error al descifrar: clave incorrecta o datos alterados.",
    Msg.BadFormat to "Los datos no tienen un formato o versión válidos.",
    Msg.NotificationChannel to "Notificaciones",
)

private val ZH = mapOf(
    Msg.NetworkUnreachable to "无法连接到服务器，请检查网络。",
    Msg.Timeout to "服务器响应超时。",
    Msg.Unauthorized to "登录已失效，请重新登录。",
    Msg.Forbidden to "你没有执行此操作的权限。",
    Msg.NotFound to "未找到。",
    Msg.Conflict to "与当前状态冲突，请刷新后重试。",
    Msg.Invalid to "数据被拒绝，请检查后重试。",
    Msg.RateLimited to "请求过于频繁，请稍候。",
    Msg.Server to "服务器出现问题，请稍后再试。",
    Msg.Parse to "服务器返回的数据格式不正确。",
    Msg.Other to "出了点问题。",
    Msg.BodyEncodeFailed to "无法将要发送的数据转换为 JSON。",
    Msg.BodyNotObject to "请求体必须是 JSON 对象。",
    Msg.DecryptFailed to "解密失败：密钥错误或数据被篡改。",
    Msg.BadFormat to "数据格式或版本无效。",
    Msg.NotificationChannel to "通知",
)
