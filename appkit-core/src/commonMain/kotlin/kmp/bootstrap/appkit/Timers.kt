package kmp.bootstrap.appkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/*
 * Hẹn giờ trong tiến trình (chạy khi app đang mở) — đổi chữ "timer" thành hai dòng:
 *
 *     val timers = AppTimers()
 *     timers.after(5.seconds) { showHint() }                          // một lần
 *     val poll = timers.every(15.minutes, immediately = true) { sync() }   // lặp, không bao giờ chồng lên nhau
 *     poll.cancel()
 *     val search = Debouncer(timers.scope, 300.milliseconds); search.submit { query(text) }   // gõ xong 300 ms mới gọi
 *
 * Việc cần chạy KHI APP ĐÃ TẮT (nhắc giờ, thông báo) thì dùng module appkit-reminder, không phải cái này.
 * Lỗi trong khối chạy chỉ được ghi log, timer vẫn sống (một lần sync hỏng không giết cả vòng lặp).
 */
class TimerHandle internal constructor(private val job: Job) {
    val isActive: Boolean get() = job.isActive
    fun cancel() = job.cancel()
}

class AppTimers(val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    /** Chạy [block] một lần sau [delay]. */
    fun after(delay: Duration, block: suspend () -> Unit): TimerHandle = TimerHandle(scope.launch {
        delay(delay); runSafely("after", block)
    })

    /**
     * Chạy [block] mỗi [interval]. Lần kế tiếp chỉ bắt đầu SAU KHI lần trước xong (không chồng lấn), nên khối chạy lâu hơn chu kỳ
     * cũng không dồn việc. [immediately] = chạy ngay một lần rồi mới chờ.
     */
    fun every(interval: Duration, immediately: Boolean = false, block: suspend () -> Unit): TimerHandle {
        require(interval.isPositive()) { "interval must be positive" }
        return TimerHandle(scope.launch {
            if (immediately) runSafely("every", block)
            while (true) { delay(interval); runSafely("every", block) }
        })
    }

    private suspend fun runSafely(what: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { AppLog.w("timer", "$what task failed", e) }
    }

    /** Huỷ mọi timer đã tạo bằng đối tượng này (khi đóng màn hình/thoát). */
    fun cancelAll() { scope.coroutineContext[Job]?.children?.forEach { it.cancel() } }
}

/** Gộp nhiều lần gọi liên tiếp thành MỘT lần, sau khi im lặng [delay] (ô tìm kiếm, lưu nháp tự động). Lần gọi mới huỷ lần chờ cũ. */
class Debouncer(private val scope: CoroutineScope, private val delay: Duration) {
    private var pending: Job? = null
    fun submit(block: suspend () -> Unit) {
        pending?.cancel()
        pending = scope.launch { delay(delay); try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { AppLog.w("timer", "debounced task failed", e) } }
    }
    fun cancel() { pending?.cancel(); pending = null }
}
