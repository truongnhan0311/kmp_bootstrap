package kmp.bootstrap.appkit.reminder

import kmp.bootstrap.appkit.AppLog
import kmp.bootstrap.appkit.AppTimers
import kmp.bootstrap.appkit.TimerHandle
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Desktop: nhắc chạy trong tiến trình (chỉ khi app đang mở) — đủ để thử logic và dùng cho app desktop. [onFire] nhận nhắc khi nổ;
 * app gọi tiếp `reminders.onFired(id)` để đặt lần kế/xoá một lần (Android và iOS tự làm bước đó).
 */
class DesktopReminderBackend(private val onFire: (Reminder) -> Unit = { AppLog.i("reminder", "fired '${it.id}': ${it.title}") }) : ReminderBackend {
    private val timers = AppTimers()
    private val handles = mutableMapOf<String, TimerHandle>()

    override suspend fun arm(reminder: Reminder, fireAtMs: Long) {
        handles.remove(reminder.id)?.cancel()
        val wait = (fireAtMs - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0)
        handles[reminder.id] = timers.after(wait.milliseconds) { handles.remove(reminder.id); onFire(reminder) }
    }

    override suspend fun disarm(id: String) { handles.remove(id)?.cancel() }
}

actual fun platformReminderBackend(appName: String): ReminderBackend = DesktopReminderBackend()
