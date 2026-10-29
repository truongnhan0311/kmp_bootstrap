package kmp.bootstrap.appkit

import kmp.bootstrap.appkit.reminder.Reminder
import kmp.bootstrap.appkit.reminder.Reminders
import kmp.bootstrap.appkit.reminder.Repeat
import kmp.bootstrap.appkit.reminder.platformReminderBackend
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

/** VÍ DỤ CHẠY ĐƯỢC của appkit-reminder. Xem ghi chú ở CoreExamples. */
class ReminderExamples {
    @Test fun schedule() = runBlocking {
        val tomorrow7am = Clock.System.now().toEpochMilliseconds() + 24 * 3_600_000L
        // region: reminder-schedule
        val reminders = Reminders(platformReminderBackend("my-app"), MemoryKeyValueStore())   // app thật: AppKit.storage.prefs
        reminders.schedule(Reminder(id = "ride", title = "Đi đạp", body = "Buổi nền tảng 90 phút", atEpochMs = tomorrow7am))
        reminders.schedule(Reminder("weigh-in", "Cân nặng", atEpochMs = tomorrow7am, repeat = Repeat.Daily))   // lặp hằng ngày, đúng giờ qua giờ mùa hè
        reminders.schedule(Reminder("ride", "Đi đạp (đổi giờ)", atEpochMs = tomorrow7am + 3_600_000))        // cùng id = THAY, không tạo thêm
        val pending = reminders.list()                       // 2 nhắc: ride, weigh-in
        reminders.cancel("weigh-in")                         // huỷ một nhắc; cancelAll() huỷ hết
        reminders.restoreAll()                               // gọi lúc mở app: nạp lại các nhắc còn hiệu lực (Android còn tự gọi sau khi máy khởi động lại)
        // endregion
        assertEquals(2, pending.size); assertEquals(listOf("ride"), reminders.list().map { it.id })
        reminders.cancelAll()
    }
}
