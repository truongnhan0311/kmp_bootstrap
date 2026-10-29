package kmp.bootstrap.appkit

import kmp.bootstrap.appkit.reminder.DesktopReminderBackend
import kmp.bootstrap.appkit.reminder.Reminder
import kmp.bootstrap.appkit.reminder.Reminders
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopReminderBackendTest {
    @Test fun aScheduledReminderReallyFiresAndACancelledOneDoesNot() = runBlocking {
        val fired = mutableListOf<String>()
        val reminders = Reminders(DesktopReminderBackend { fired += it.id }, MemoryKeyValueStore())
        val soon = Clock.System.now().toEpochMilliseconds() + 150
        reminders.schedule(Reminder("fires", "T", atEpochMs = soon)); reminders.schedule(Reminder("cancelled", "T", atEpochMs = soon)); reminders.cancel("cancelled")
        delay(600)
        assertEquals(listOf("fires"), fired); assertTrue(reminders.onFired("fires") != null)
    }
}
