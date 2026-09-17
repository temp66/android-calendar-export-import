package dev.calendar.exportimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full export -> import -> verify cycle against the fake provider. */
class ExportImportTest {

    private fun sourceDevice(): FakeGateway {
        val source = FakeGateway()
        source.addCalendar(1L, "Work", "me@example.test", "com.google")
        source.addCalendar(2L, "Personal", "me@example.com", "LOCAL")
        source.addCalendar(3L, "Holidays", "holidays@example.test", "com.google")
        // A read-only calendar must never be offered as a destination.
        source.rows.getValue(Table.CALENDARS).last()[Calendars.ACCESS_LEVEL] = 200L

        source.addEvent(10L, 1L, "Standup", 1_700_000_000_000L, 1_700_000_900_000L)
        source.addEvent(
            11L, 1L, "Weekly review", 1_700_000_000_000L, null,
            rrule = "FREQ=WEEKLY;BYDAY=MO", duration = "PT3600S"
        )
        source.addEvent(
            12L, 1L, "Weekly review (moved)", 1_700_604_800_000L, 1_700_608_400_000L,
            originalId = 11L, originalInstanceTime = 1_700_604_800_000L
        )
        source.addEvent(13L, 2L, "Dentist", 1_700_100_000_000L, 1_700_100_000_000L)

        source.addAttendee(10L, "Ana", "ana@example.test")
        source.addAttendee(10L, "Bo", "bo@example.test", status = 4L)
        source.addReminder(10L, 15L)
        source.addReminder(13L, -1L, method = 0L)
        // One event belongs to an app that owns its richer UI. The pair is opaque to us, but it
        // is part of the event's payload: see ColumnPolicy.NOT_COPIED_AS_IS.
        source.rows.getValue(Table.EVENTS).first().apply {
            this[Events.CUSTOM_APP_PACKAGE] = "com.example.custom"
            this[Events.CUSTOM_APP_URI] = "content://com.example.custom/events/7"
        }
        return source
    }

    private fun backupOf(source: FakeGateway): Backup =
        Exporter(source, "test", { mapOf("device" to "fake") }).export()

    @Test
    fun `export captures every table and skips pre-edit copies`() {
        val source = sourceDevice()
        source.rows.getValue(Table.EVENTS).first()[Events.LAST_SYNCED] = 1L

        val backup = backupOf(source)

        assertEquals(3L, backup.manifest["count.calendars"])
        assertEquals(3L, backup.manifest["count.events"])
        assertEquals(2L, backup.manifest["count.attendees"])
        assertEquals(2L, backup.manifest["count.reminders"])
        assertTrue(
            "every event carries an import key",
            backup.rows(Table.EVENTS).all { it[ImportKeys.COLUMN] is String }
        )
        assertTrue(
            "sync-visible columns are exported even though they are not replayed",
            backup.rows(Table.EVENTS).any { it[Events.SYNC_DATA1] != null }
        )
    }

    @Test
    fun `import inserts events, overrides, attendees and reminders into an existing calendar`() {
        val destination = FakeGateway()
        destination.addCalendar(100L, "My calendar", "me@example.test", "com.google")

        val backup = backupOf(sourceDevice())
        val report = Importer(destination, {}).import(
            backup,
            destinationCalendarId = 100L,
            sourceCalendarIds = setOf(1L, 2L),
            skipExisting = true
        )

        assertEquals(4, report.considered)
        assertEquals(4, report.inserted)
        assertEquals(0, report.failed)
        assertEquals(1, report.exceptionsInserted)
        assertEquals(2, report.attendeesInserted)
        assertEquals(2, report.remindersInserted)

        val inserted = destination.rows.getValue(Table.EVENTS)
        assertEquals(4, inserted.size)

        val weekly = inserted.first { it[Events.TITLE] == "Weekly review" }
        val moved = inserted.first { it[Events.TITLE] == "Weekly review (moved)" }
        assertEquals(
            "the override points at the new parent id",
            weekly[Events._ID],
            moved[Events.ORIGINAL_ID]
        )
        // The override keeps its own iCalendar UID rather than inheriting the series UID; the
        // provider links it to the series through original_id.
        assertEquals("uid-12@example.test", moved[Events.UID_2445])

        assertTrue(
            "the destination calendar's own sync adapter will upload these",
            inserted.all { it[Events.DIRTY].asLongOrNull() == 1L }
        )
    }

    @Test
    fun `import never writes identity or sync fields`() {
        val destination = FakeGateway()
        destination.addCalendar(100L, "My calendar", "me@example.test", "com.google")

        Importer(destination, {}).import(
            backupOf(sourceDevice()), 100L, setOf(1L, 2L), skipExisting = false
        )

        val forbidden = ColumnPolicy.SYNC_ONLY_EVENT_COLUMNS +
            ColumnPolicy.PROVIDER_OWNED_EVENT_COLUMNS +
            ColumnPolicy.NOT_COPIED_AS_IS +
            ColumnPolicy.COLOR_KEY_COLUMNS +
            setOf("_id", "lastDate", "displayColor", "hasAlarm", "original_id")
        forbidden.forEach { column ->
            assertFalse("$column must never be written", column in destination.writtenColumns)
        }
        assertFalse("attendeeIdentity must not be written", Attendees.IDENTITY in destination.writtenColumns)
        assertFalse("attendeeIdNamespace must not be written", Attendees.ID_NAMESPACE in destination.writtenColumns)
    }

    @Test
    fun `the custom app link is copied as-is`() {
        val destination = FakeGateway()
        destination.addCalendar(100L, "My calendar", "me@example.test", "com.google")

        Importer(destination, {}).import(
            backupOf(sourceDevice()), 100L, setOf(1L, 2L), skipExisting = true
        )

        val standup = destination.rows.getValue(Table.EVENTS)
            .first { it[Events.TITLE] == "Standup" }
        assertEquals("com.example.custom", standup[Events.CUSTOM_APP_PACKAGE])
        assertEquals("content://com.example.custom/events/7", standup[Events.CUSTOM_APP_URI])
        // Events without such a link stay without one.
        val dentist = destination.rows.getValue(Table.EVENTS).first { it[Events.TITLE] == "Dentist" }
        assertNull(dentist[Events.CUSTOM_APP_PACKAGE])
        assertNull(dentist[Events.CUSTOM_APP_URI])
    }

    @Test
    fun `a clean round trip verifies with no differences`() {
        val destination = FakeGateway()
        destination.addCalendar(100L, "My calendar", "me@example.test", "com.google")

        val backup = backupOf(sourceDevice())
        Importer(destination, {}).import(backup, 100L, setOf(1L, 2L), skipExisting = true)

        val report = Verifier(destination).verify(backup, 100L, setOf(1L, 2L))

        assertTrue(report, report.contains("Missing:                         0"))
        assertTrue(report, report.contains("Events with field differences:   0"))
        assertTrue(report, report.contains("No differences in the fields this import can preserve."))
    }

    @Test
    fun `verify reports a field that was altered after import`() {
        val destination = FakeGateway()
        destination.addCalendar(100L, "My calendar", "me@example.test", "com.google")

        val backup = backupOf(sourceDevice())
        Importer(destination, {}).import(backup, 100L, setOf(1L, 2L), skipExisting = true)

        val standup = destination.rows.getValue(Table.EVENTS)
            .first { it[Events.TITLE] == "Standup" }
        standup[Events.EVENT_LOCATION] = "Somewhere else"
        standup[Events.EVENT_COLOR] = 42L

        val report = Verifier(destination).verify(backup, 100L, setOf(1L, 2L))

        assertTrue(report, report.contains("Events with field differences:   1"))
        assertTrue(report, report.contains("eventLocation: null -> Somewhere else"))
        assertTrue(report, report.contains("eventColor: null -> 42"))
    }

    @Test
    fun `importing the same backup twice creates nothing the second time`() {
        val destination = FakeGateway()
        destination.addCalendar(100L, "My calendar", "me@example.test", "com.google")
        val backup = backupOf(sourceDevice())

        val first = Importer(destination, {}).import(backup, 100L, setOf(1L, 2L), skipExisting = true)
        val second = Importer(destination, {}).import(backup, 100L, setOf(1L, 2L), skipExisting = true)

        assertEquals(4, first.inserted)
        assertEquals(0, second.inserted)
        assertEquals(4, second.skipped)
        assertEquals(4, destination.rows.getValue(Table.EVENTS).size)
    }

    @Test
    fun `a rejected row is reported and does not roll back the rest of its batch`() {
        val source = sourceDevice()
        // The provider rejects an RRULE it cannot parse; the importer must lose only this row.
        source.addEvent(
            14L, 1L, "Broken recurrence", 1_700_200_000_000L, null,
            rrule = "EVERY_MONDAY", duration = "PT3600S"
        )
        val destination = FakeGateway()
        destination.addCalendar(100L, "My calendar", "me@example.test", "com.google")

        val report = Importer(destination, {}).import(
            backupOf(source), 100L, setOf(1L, 2L), skipExisting = false
        )

        assertEquals(1, report.failed)
        assertEquals(4, report.inserted)
        assertEquals(4, destination.rows.getValue(Table.EVENTS).size)
        assertTrue(report.problems.any { it.contains("Invalid recurrence rule") })
    }

    @Test
    fun `an override whose series is not selected is kept and reported`() {
        val destination = FakeGateway()
        destination.addCalendar(100L, "My calendar", "me@example.test", "com.google")
        val backup = Backup()
        val source = sourceDevice()
        backup.rows(Table.EVENTS).addAll(source.rows.getValue(Table.EVENTS).filter { it[Events._ID] == 12L })
        backup.rows(Table.CALENDARS).addAll(source.rows.getValue(Table.CALENDARS).filter { it[Calendars._ID] == 1L })
        backup.rows(Table.EVENTS).forEach { it[ImportKeys.COLUMN] = ImportKeys.uidFor(it) }

        val report = Importer(destination, {}).import(backup, 100L, setOf(1L), skipExisting = false)

        assertEquals(1, report.inserted)
        assertTrue(report.problems.any { it.contains("standalone event") })
        assertNotNull(
            destination.rows.getValue(Table.EVENTS).first()[Events.TITLE]
        )
    }

    @Test
    fun `a five thousand event calendar imports in reasonable time`() {
        val source = FakeGateway()
        source.addCalendar(1L, "Big", "me@example.test", "com.google")
        for (i in 0 until 5_000) {
            source.addEvent(
                1000L + i, 1L, "Event $i", 1_700_000_000_000L + i * 3_600_000L,
                1_700_000_000_000L + i * 3_600_000L + 600_000L
            )
        }
        val destination = FakeGateway()
        destination.addCalendar(100L, "My calendar", "me@example.test", "com.google")

        val started = System.currentTimeMillis()
        val report = Importer(destination, {}).import(
            backupOf(source), 100L, setOf(1L), skipExisting = true
        )
        val elapsed = System.currentTimeMillis() - started

        assertEquals(5_000, report.inserted)
        assertEquals(5_000, destination.rows.getValue(Table.EVENTS).size)
        assertTrue("import took ${elapsed}ms", elapsed < 30_000)
    }
}
