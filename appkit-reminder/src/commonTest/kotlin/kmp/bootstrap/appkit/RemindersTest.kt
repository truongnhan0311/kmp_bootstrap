package kmp.bootstrap.appkit

import kmp.bootstrap.appkit.reminder.Reminder
import kmp.bootstrap.appkit.reminder.ReminderBackend
import kmp.bootstrap.appkit.reminder.Reminders
import kmp.bootstrap.appkit.reminder.Repeat
import kmp.bootstrap.appkit.reminder.nextFireAfter
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeBackend : ReminderBackend {
    val armed = mutableMapOf<String, Long>()
    override suspend fun arm(reminder: Reminder, fireAtMs: Long) { armed[reminder.id] = fireAtMs }
    override suspend fun disarm(id: String) { armed.remove(id) }
}

class RemindersTest {
    private val hcm = TimeZone.of("Asia/Ho_Chi_Minh")
    private val ny = TimeZone.of("America/New_York")
    private var now = 1_000_000L
    private fun rem(backend: FakeBackend, store: KeyValueStore = MemoryKeyValueStore(), zone: TimeZone = hcm) = Reminders(backend, store, { now }, zone)

    @Test fun schedulingArmsAndReschedulingSameIdReplaces() = runTest {
        val b = FakeBackend(); val r = rem(b)
        r.schedule(Reminder("a", "T", atEpochMs = now + 1000)); r.schedule(Reminder("a", "T2", atEpochMs = now + 5000))
        assertEquals(listOf("T2"), r.list().map { it.title }); assertEquals(now + 5000, b.armed["a"])
    }

    @Test fun pastNonRepeatingAndBlankFieldsAreRejected() = runTest {
        val r = rem(FakeBackend())
        assertFailsWith<IllegalArgumentException> { r.schedule(Reminder("a", "T", atEpochMs = now - 1)) }
        assertFailsWith<IllegalArgumentException> { r.schedule(Reminder("", "T", atEpochMs = now + 1)) }
        assertFailsWith<IllegalArgumentException> { r.schedule(Reminder("a", " ", atEpochMs = now + 1)) }
        assertTrue(r.list().isEmpty())
    }

    @Test fun cancelRemovesFromBothListAndOs() = runTest {
        val b = FakeBackend(); val r = rem(b)
        r.schedule(Reminder("a", "T", atEpochMs = now + 1)); r.schedule(Reminder("b", "T", atEpochMs = now + 2))
        r.cancel("a"); assertEquals(setOf("b"), b.armed.keys); r.cancelAll(); assertTrue(b.armed.isEmpty() && r.list().isEmpty())
    }

    @Test fun dailyRepeatKeepsWallClockTimeAcrossDaylightSaving() {
        // 8:00 giờ New York ngày 8/3/2025 (trước DST), nhắc hằng ngày; sang 9/3 đồng hồ nhảy 1 giờ — vẫn phải 8:00 giờ địa phương
        val first = LocalDateTime(2025, 3, 8, 8, 0).toInstant(ny).toEpochMilliseconds()
        val r = Reminder("w", "T", atEpochMs = first, repeat = Repeat.Daily)
        val next = r.nextFireAfter(first + 1, ny)!!
        val local = Instant.fromEpochMilliseconds(next).toLocalDateTime(ny)
        assertEquals(9, local.dayOfMonth); assertEquals(8, local.hour); assertEquals(0, local.minute)
        assertEquals(23 * 3_600_000L, next - first, "ngày có DST chỉ dài 23 giờ — không được cộng cứng 24 giờ")
    }

    @Test fun weeklyAndFarPastStartStillLandInTheFutureOnTheSameWeekday() {
        val start = LocalDateTime(2020, 1, 6, 7, 30).toInstant(hcm).toEpochMilliseconds()   // thứ hai
        val nowMs = LocalDateTime(2026, 10, 29, 12, 0).toInstant(hcm).toEpochMilliseconds()  // thứ năm
        val next = Reminder("w", "T", atEpochMs = start, repeat = Repeat.Weekly).nextFireAfter(nowMs, hcm)!!
        val local = Instant.fromEpochMilliseconds(next).toLocalDateTime(hcm)
        assertTrue(next > nowMs); assertEquals(kotlinx.datetime.DayOfWeek.MONDAY, local.dayOfWeek); assertEquals(7, local.hour); assertEquals(30, local.minute)
        assertEquals(2026, local.year); assertEquals(11, local.monthNumber); assertEquals(2, local.dayOfMonth)
    }

    @Test fun firedOneShotIsRemovedAndRepeatingIsRearmed() = runTest {
        val b = FakeBackend(); val r = rem(b)
        r.schedule(Reminder("once", "T", atEpochMs = now + 1000)); r.schedule(Reminder("daily", "D", atEpochMs = now + 1000, repeat = Repeat.Daily))
        now += 2000
        assertEquals("T", r.onFired("once")?.title); assertEquals(listOf("daily"), r.list().map { it.id })
        assertEquals("D", r.onFired("daily")?.title); assertTrue(b.armed["daily"]!! > now, "lần kế đã được đặt")
        assertNull(r.onFired("nope"))
    }

    @Test fun restoreAllRearmsLiveOnesAndDropsExpiredOneShots() = runTest {
        val store = MemoryKeyValueStore(); val b1 = FakeBackend()
        rem(b1, store).apply { schedule(Reminder("soon", "T", atEpochMs = now + 10_000)); schedule(Reminder("gone", "T", atEpochMs = now + 500)); schedule(Reminder("d", "T", atEpochMs = now + 500, repeat = Repeat.Daily)) }
        now += 5_000  // máy khởi động lại, "gone" đã qua giờ
        val b2 = FakeBackend(); val r2 = rem(b2, store); r2.restoreAll()
        assertEquals(setOf("soon", "d"), b2.armed.keys); assertEquals(setOf("soon", "d"), r2.list().map { it.id }.toSet())
    }

    @Test fun corruptStoreDoesNotCrash() = runTest {
        val store = MemoryKeyValueStore().also { it.put("reminders.v1", "{hỏng") }
        assertTrue(rem(FakeBackend(), store).list().isEmpty())
    }
}
