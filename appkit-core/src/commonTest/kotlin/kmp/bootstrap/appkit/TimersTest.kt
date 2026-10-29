package kmp.bootstrap.appkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TimersTest {
    @Test fun afterFiresOnceAtTheRightTime() = runTest {
        val t = AppTimers(this.backgroundScope); var n = 0
        t.after(5.seconds) { n++ }
        advanceTimeBy(4_999); runCurrent(); assertEquals(0, n)
        advanceTimeBy(2); runCurrent(); assertEquals(1, n)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, n, "chỉ một lần")
    }

    @Test fun everyRepeatsImmediatelyOptionalAndCanBeCancelled() = runTest {
        val t = AppTimers(this.backgroundScope); var n = 0
        val h = t.every(10.seconds, immediately = true) { n++ }
        runCurrent(); assertEquals(1, n)
        advanceTimeBy(10_001); runCurrent(); assertEquals(2, n)
        advanceTimeBy(20_000); runCurrent(); assertEquals(4, n)
        h.cancel(); advanceTimeBy(60_000); runCurrent(); assertEquals(4, n); assertFalse(h.isActive)
    }

    @Test fun slowRunsNeverOverlapAndFailuresDoNotKillTheLoop() = runTest {
        val t = AppTimers(this.backgroundScope); var running = 0; var maxRunning = 0; var calls = 0
        t.every(1.seconds) { running++; maxRunning = maxOf(maxRunning, running); calls++; delay(3.seconds); running--; if (calls == 1) error("boom") }
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, maxRunning, "không chồng lấn dù chạy lâu hơn chu kỳ"); assertTrue(calls >= 3, "lần đầu lỗi vẫn tiếp tục ($calls)")
    }

    @Test fun intervalMustBePositive() = runTest {
        assertFailsWith<IllegalArgumentException> { AppTimers(this.backgroundScope).every(0.seconds) {} }
    }

    @Test fun debouncerRunsOnlyTheLastCallAfterQuietPeriod() = runTest {
        val d = Debouncer(this.backgroundScope, 300.milliseconds); val seen = mutableListOf<String>()
        d.submit { seen += "a" }; advanceTimeBy(100); d.submit { seen += "ab" }; advanceTimeBy(100); d.submit { seen += "abc" }
        advanceTimeBy(299); runCurrent(); assertTrue(seen.isEmpty())
        advanceTimeBy(2); runCurrent(); assertEquals(listOf("abc"), seen)
        d.submit { seen += "x" }; d.cancel(); advanceTimeBy(1_000); runCurrent(); assertEquals(listOf("abc"), seen)
    }

    @Test fun cancelAllStopsEveryTimerOfThatInstance() = runTest {
        val t = AppTimers(childScopeOf(backgroundScope)); var n = 0
        t.every(1.seconds) { n++ }; t.after(2.seconds) { n += 100 }
        advanceTimeBy(1_500); runCurrent(); assertEquals(1, n)
        t.cancelAll(); advanceTimeBy(10_000); runCurrent(); assertEquals(1, n)
    }
}

/** Phạm vi riêng (để cancelAll chỉ huỷ timer của chính nó) nhưng vẫn là con của backgroundScope nên test dọn được. */
private fun childScopeOf(parent: kotlinx.coroutines.CoroutineScope) =
    kotlinx.coroutines.CoroutineScope(parent.coroutineContext + kotlinx.coroutines.SupervisorJob(parent.coroutineContext[kotlinx.coroutines.Job]))
