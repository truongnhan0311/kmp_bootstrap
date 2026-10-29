package kmp.bootstrap.appkit.reminder

import kmp.bootstrap.appkit.AppLog
import kmp.bootstrap.appkit.KeyValueStore
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * Nhắc giờ bằng THÔNG BÁO CỤC BỘ — chạy cả khi app đã tắt (khác AppTimers chỉ chạy lúc app mở).
 *
 *     val reminders = Reminders(platformReminderBackend("my-app"), AppKit.storage.prefs)
 *     reminders.schedule(Reminder("ride-tomorrow", "Đi đạp", "Buổi nền tảng 90 phút", atEpochMs = tomorrow7am))
 *     reminders.schedule(Reminder("weigh-in", "Cân nặng", "", atEpochMs = next8am, repeat = Repeat.Daily))
 *     reminders.cancel("ride-tomorrow")
 *     reminders.restoreAll()        // gọi lúc mở app (và Android gọi sau khi máy khởi động lại) để nạp lại các hẹn còn hiệu lực
 *
 * Mỗi nhắc có `id` do app đặt: đặt lại cùng `id` là THAY, không tạo thêm. Nhắc lặp giữ ĐÚNG GIỜ TRÊN ĐỒNG HỒ qua giờ mùa hè
 * (8:00 vẫn là 8:00), không cộng cứng 24 giờ. Bấm thông báo mở app qua `PushNavigation` kiểu `type = "reminder"`, `id`.
 * Xin quyền hiện thông báo là việc của app (Android 13+: AppKitPush.requestPermission; iOS: UNUserNotificationCenter.requestAuthorization).
 */
enum class Repeat { None, Daily, Weekly }

@Serializable
data class Reminder(
    val id: String,
    val title: String,
    val body: String = "",
    /** Thời điểm nhắc đầu tiên (epoch ms). */
    val atEpochMs: Long,
    val repeat: Repeat = Repeat.None,
    /** Dữ liệu đi kèm, trả lại khi bấm thông báo. */
    val data: Map<String, String> = emptyMap(),
)

/** Phần theo nền tảng: đặt báo thức/lịch thông báo thật của hệ điều hành. */
interface ReminderBackend {
    /** Đặt (hoặc thay) một lần nhắc vào lúc [fireAtMs]. Với nhắc lặp, [Reminders.onFired] sẽ được gọi để đặt lần kế. */
    suspend fun arm(reminder: Reminder, fireAtMs: Long)
    suspend fun disarm(id: String)
}

expect fun platformReminderBackend(appName: String): ReminderBackend

/** Lần nhắc kế tiếp SAU [nowMs] (null nếu không còn). Nhắc lặp giữ đúng giờ trên đồng hồ theo [zone]. */
fun Reminder.nextFireAfter(nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
    if (atEpochMs > nowMs) return atEpochMs
    val unit = when (repeat) { Repeat.None -> return null; Repeat.Daily -> DateTimeUnit.DAY; Repeat.Weekly -> DateTimeUnit.WEEK }
    var t = Instant.fromEpochMilliseconds(atEpochMs)
    // nhảy thẳng gần tới now rồi bước từng chu kỳ, để nhắc đặt từ rất lâu không phải lặp hàng nghìn lần
    val cycleMs = if (repeat == Repeat.Daily) 86_400_000L else 7 * 86_400_000L
    val skip = ((nowMs - atEpochMs) / cycleMs - 1).coerceAtLeast(0)
    if (skip > 0) t = t.plus(skip.toInt(), unit, zone)
    while (t.toEpochMilliseconds() <= nowMs) t = t.plus(1, unit, zone)
    return t.toEpochMilliseconds()
}

class Reminders(
    private val backend: ReminderBackend,
    private val store: KeyValueStore,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Reminder.serializer())

    fun list(): List<Reminder> = store.get(KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    private fun save(all: List<Reminder>) = store.put(KEY, if (all.isEmpty()) null else json.encodeToString(serializer, all))

    /** Đặt hoặc thay một nhắc. Ném [IllegalArgumentException] nếu không bao giờ nhắc được (đã qua giờ và không lặp, id/tiêu đề rỗng). */
    suspend fun schedule(reminder: Reminder) {
        require(reminder.id.isNotBlank()) { "reminder id must not be blank" }
        require(reminder.title.isNotBlank()) { "reminder title must not be blank" }
        val fireAt = requireNotNull(reminder.nextFireAfter(now(), zone)) { "reminder '${reminder.id}' is in the past and does not repeat" }
        save(list().filterNot { it.id == reminder.id } + reminder)
        backend.arm(reminder, fireAt)
        AppLog.i("reminder", "scheduled '${reminder.id}' repeat=${reminder.repeat}")
    }

    suspend fun cancel(id: String) { save(list().filterNot { it.id == id }); backend.disarm(id) }

    suspend fun cancelAll() { list().forEach { backend.disarm(it.id) }; save(emptyList()) }

    /** Nạp lại mọi hẹn còn hiệu lực (mở app, hoặc sau khi máy khởi động lại); nhắc một lần đã qua giờ bị bỏ. */
    suspend fun restoreAll() {
        val live = list().filter { it.nextFireAfter(now(), zone) != null }
        live.forEach { backend.arm(it, it.nextFireAfter(now(), zone)!!) }
        if (live.size != list().size) save(live)
    }

    /** Nền tảng gọi khi một nhắc vừa nổ: lặp thì đặt lần kế, một lần thì xoá khỏi danh sách. Trả nhắc đó (để hiện/điều hướng). */
    suspend fun onFired(id: String): Reminder? {
        val r = list().firstOrNull { it.id == id } ?: return null
        val next = if (r.repeat == Repeat.None) null else r.copy(atEpochMs = r.atEpochMs).nextFireAfter(now(), zone)
        if (next != null) backend.arm(r, next) else save(list().filterNot { it.id == id })
        return r
    }

    private companion object { const val KEY = "reminders.v1" }
}
