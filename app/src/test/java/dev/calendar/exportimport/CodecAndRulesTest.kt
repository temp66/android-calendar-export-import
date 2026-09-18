package dev.calendar.exportimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecAndRulesTest {

    @Test
    fun `json round trip keeps types and nulls`() {
        val backup = Backup()
        backup.manifest["schemaVersion"] = SCHEMA_VERSION.toLong()
        backup.manifest["note"] = "unicode ✓ line\nbreak"
        backup.rows(Table.EVENTS).add(
            linkedMapOf(
                Events._ID to 7L,
                Events.TITLE to "Standup",
                Events.DESCRIPTION to "line one\nline two, \"quoted\"",
                Events.DTSTART to 1_700_000_000_000L,
                Events.DTEND to null,
                Events.DURATION to "PT900S",
                Events.EVENT_COLOR to null,
                Events.EVENT_TIMEZONE to "Europe/Berlin",
                Events.RRULE to "FREQ=WEEKLY;BYDAY=MO,TU\nFREQ=DAILY",
                Events.ALL_DAY to 1L,
                "blob" to byteArrayOf(0, 1, 2, 3, 127, -1)
            )
        )

        val decoded = JsonCodec.decode(JsonCodec.encode(backup))
        assertEquals(SCHEMA_VERSION.toLong(), decoded.manifest["schemaVersion"])
        assertEquals("unicode ✓ line\nbreak", decoded.manifest["note"])

        val row = decoded.rows(Table.EVENTS).single()
        assertEquals(7L, row[Events._ID])
        assertEquals("line one\nline two, \"quoted\"", row[Events.DESCRIPTION])
        assertEquals(1_700_000_000_000L, row[Events.DTSTART])
        assertNull(row[Events.DTEND])
        assertNull(row[Events.EVENT_COLOR])
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU\nFREQ=DAILY", row[Events.RRULE])
        assertTrue(
            "blob survives",
            (row["blob"] as ByteArray).contentEquals(byteArrayOf(0, 1, 2, 3, 127, -1))
        )
    }

    @Test
    fun `null colour is preserved and never becomes zero`() {
        val row = linkedMapOf<String, Any?>(Events.EVENT_COLOR to null, Events.TITLE to "x")
        val decoded = JsonCodec.decode(
            JsonCodec.encode(Backup().apply { rows(Table.EVENTS).add(row) })
        ).rows(Table.EVENTS).single()
        assertNull(decoded[Events.EVENT_COLOR])
    }

    @Test
    fun `schema version is readable without a full decode`() {
        val text = """{"manifest":{"schemaVersion":1},"events":[]}"""
        assertEquals(1, JsonCodec.schemaVersionOf(text))
    }

    @Test
    fun `event with both dtend and duration keeps duration when recurring`() {
        val source = baseEvent(
            Events.RRULE to "FREQ=WEEKLY",
            Events.DTEND to 1_700_003_600_000L,
            Events.DURATION to "PT3600S"
        )
        val built = EventRules.buildEvent(source, "uid")
        assertNull(built[Events.DTEND])
        assertEquals("PT3600S", built[Events.DURATION])
    }

    @Test
    fun `event with both dtend and duration keeps dtend when not recurring`() {
        val source = baseEvent(
            Events.DTEND to 1_700_003_600_000L,
            Events.DURATION to "PT3600S"
        )
        val built = EventRules.buildEvent(source, "uid")
        assertEquals(1_700_003_600_000L, built[Events.DTEND])
        assertNull(built[Events.DURATION])
    }

    @Test(expected = EventRules.Rejected::class)
    fun `event without dtstart is rejected`() {
        val source = baseEvent().apply { remove(Events.DTSTART) }
        EventRules.buildEvent(source, "uid")
    }

    @Test(expected = EventRules.Rejected::class)
    fun `event with neither dtend nor duration is rejected`() {
        val source = baseEvent().apply { remove(Events.DTEND) }
        EventRules.buildEvent(source, "uid")
    }

    @Test
    fun `fields that must not travel are dropped`() {
        val source = baseEvent(
            Events.SELF_ATTENDEE_STATUS to 1L,
            Events.ORIGINAL_SYNC_ID to "src-sync-1",
            Events.EVENT_COLOR_KEY to "color-key-1",
            Events.LAST_DATE to 1_700_003_600_000L,
            Events.DISPLAY_COLOR to 123L,
            Events.HAS_ALARM to 1L,
            Events._SYNC_ID to "src-1",
            Events.SYNC_DATA1 to "private",
            Events.LAST_SYNCED to 0L,
            Events.DIRTY to 0L,
            Events.DELETED to 0L,
            "calendar_color" to 55L,
            "account_name" to "me@example.test",
            Events._ID to 99L
        )
        val built = EventRules.buildEvent(source, "uid-1")
        ColumnPolicy.NOT_COPIED_AS_IS.forEach { assertTrue("$it must be dropped", it !in built) }
        ColumnPolicy.SYNC_ONLY_EVENT_COLUMNS.forEach { assertTrue("$it must be dropped", it !in built) }
        ColumnPolicy.PROVIDER_OWNED_EVENT_COLUMNS.forEach { assertTrue("$it must be dropped", it !in built) }
        ColumnPolicy.COLOR_KEY_COLUMNS.forEach { assertTrue("$it must be dropped", it !in built) }
        ColumnPolicy.NOT_WRITTEN_EVENT_COLUMNS.forEach { assertTrue("$it must be dropped", it !in built) }
        assertTrue("_id must be dropped", Events._ID !in built)
        assertEquals("uid-1", built[Events.UID_2445])
        assertEquals(1_700_000_000_000L, built[Events.DTSTART])
    }

    @Test
    fun `the custom app link survives both the event and the override path`() {
        // The two paths differ: an event is scrubbed and normalised, an override is additionally
        // filtered down to ColumnPolicy.ALLOWED_IN_EXCEPTION, so a field missing from that set
        // would vanish for overrides alone.
        val source = baseEvent(
            Events.CUSTOM_APP_PACKAGE to "com.example.custom",
            Events.CUSTOM_APP_URI to "content://com.example.custom/events/7"
        )

        val event = EventRules.buildEvent(source, "uid-1")
        assertEquals("com.example.custom", event[Events.CUSTOM_APP_PACKAGE])
        assertEquals("content://com.example.custom/events/7", event[Events.CUSTOM_APP_URI])

        source[Events.ORIGINAL_ID] = 5L
        source[Events.ORIGINAL_INSTANCE_TIME] = 1_700_001_800_000L
        val override = EventRules.buildException(source, "uid-5")
        assertEquals("com.example.custom", override[Events.CUSTOM_APP_PACKAGE])
        assertEquals("content://com.example.custom/events/7", override[Events.CUSTOM_APP_URI])
    }

    @Test
    fun `sync supplied flags are copied, and the provider's override whitelist still applies`() {
        // hasAttendeeData and isOrganizer are not computed by the provider; Google's own rows
        // carry both as 1 even with no attendee rows, so they travel like any payload column.
        val source = baseEvent(
            Events.HAS_ATTENDEE_DATA to 1L,
            Events.IS_ORGANIZER to 1L
        )

        val event = EventRules.buildEvent(source, "uid-1")
        assertEquals(1L, builtValue(event, Events.HAS_ATTENDEE_DATA))
        assertEquals(1L, builtValue(event, Events.IS_ORGANIZER))

        source[Events.ORIGINAL_ID] = 5L
        source[Events.ORIGINAL_INSTANCE_TIME] = 1_700_001_800_000L
        val override = EventRules.buildException(source, "uid-5")
        assertEquals(1L, builtValue(override, Events.HAS_ATTENDEE_DATA))
        // isOrganizer is not in the provider's exception whitelist, so it cannot be set on an
        // override; the clone inherits it from the series.
        assertNull(override[Events.IS_ORGANIZER])
    }

    private fun builtValue(row: Row, column: String): Long? = row[column].asLongOrNull()

    @Test
    fun `override keeps only what an exception may set and synthesises a duration`() {
        val source = baseEvent(
            Events.ORIGINAL_ID to 5L,
            Events.ORIGINAL_INSTANCE_TIME to 1_700_001_800_000L,
            Events.CALENDAR_ID to 1L,
            Events.ORGANIZER to "boss@example.test"
        )
        // The source exception carries an end time but no duration, which is the shape the
        // importer has to convert.
        source[Events.DURATION] = null
        source[Events.DTEND] = 1_700_000_600_000L

        val built = EventRules.buildException(source, "uid-5")
        built.keys.forEach { assertTrue("$it is not allowed in an exception", it in ColumnPolicy.ALLOWED_IN_EXCEPTION) }
        assertTrue("dtend must not be sent for an exception", Events.DTEND !in built)
        assertEquals("PT600S", built[Events.DURATION])
        assertEquals(1_700_001_800_000L, built[Events.ORIGINAL_INSTANCE_TIME])
        assertEquals("boss@example.test", built[Events.ORGANIZER])
    }

    @Test
    fun `duration strings follow rfc 2445`() {
        assertEquals("PT900S", EventRules.durationString(900_000L, allDay = false))
        assertEquals("PT1S", EventRules.durationString(1_000L, allDay = false))
        assertEquals("P1D", EventRules.durationString(86_400_000L, allDay = false))
        assertEquals("P1DT60S", EventRules.durationString(86_460_000L, allDay = false))
        assertEquals("P2D", EventRules.durationString(2 * 86_400_000L, allDay = true))
    }

    private fun baseEvent(vararg extra: Pair<String, out Any?>): Row {
        val row: Row = linkedMapOf(
            Events._ID to 1L,
            Events.CALENDAR_ID to 1L,
            Events.TITLE to "Standup",
            Events.DTSTART to 1_700_000_000_000L,
            Events.DTEND to 1_700_000_600_000L,
            Events.EVENT_TIMEZONE to "Europe/Berlin",
            Events.ALL_DAY to 0L
        )
        extra.forEach { (column, value) -> row[column] = value }
        return row
    }
}
