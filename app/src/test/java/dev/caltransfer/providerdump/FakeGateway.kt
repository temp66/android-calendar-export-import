package dev.caltransfer.providerdump

/**
 * In-memory provider that enforces the rules the real CalendarProvider2 enforces, so the
 * transfer logic is exercised on the JVM without a device. Every rule below cites the AOSP
 * code it mirrors.
 */
class FakeGateway : ProviderGateway {

    val rows: MutableMap<Table, MutableList<Row>> =
        Table.entries.associateWith { mutableListOf<Row>() }.toMutableMap()

    private var nextId = 1L

    /** Every column name that was written across all inserts, for policy assertions. */
    val writtenColumns = mutableSetOf<String>()

    override fun writableCalendars(): List<WritableCalendar> =
        rows.getValue(Table.CALENDARS).mapNotNull { row ->
            val id = row[Calendars._ID].asLongOrNull() ?: return@mapNotNull null
            if (row[Calendars.DELETED].asLongOrNull() == 1L) return@mapNotNull null
            val access = row[Calendars.ACCESS_LEVEL].asLongOrNull() ?: 0L
            if (access < Calendars.CAL_ACCESS_CONTRIBUTOR) return@mapNotNull null
            WritableCalendar(
                id = id,
                accountName = row[Calendars.ACCOUNT_NAME] as? String,
                accountType = row[Calendars.ACCOUNT_TYPE] as? String,
                displayName = row[Calendars.DISPLAY_NAME] as? String
            )
        }

    override fun query(table: Table, selection: String?, selectionArgs: Array<String>?): List<Row> {
        var result = rows.getValue(table).toList()
        if (selection != null) {
            if (selection.contains("${Events.CALENDAR_ID}=?")) {
                val wanted = selectionArgs?.firstOrNull()?.toLongOrNull()
                result = result.filter { it[Events.CALENDAR_ID].asLongOrNull() == wanted }
            }
            if (selection.contains(Events.LAST_SYNCED)) {
                result = result.filter { it[Events.LAST_SYNCED].asLongOrNull() != 1L }
            }
        }
        return result.map { LinkedHashMap(it) }
    }

    override fun insert(table: Table, values: Row): Long = insertInternal(table, values)

    override fun insertException(parentEventId: Long, values: Row): Long {
        // CalendarProvider2.checkAllowedInException + DONT_CLONE_INTO_EXCEPTION.
        values.keys.forEach { column ->
            if (column !in ColumnPolicy.ALLOWED_IN_EXCEPTION) {
                throw IllegalArgumentException("Exceptions can't overwrite $column")
            }
        }
        if (values[Events.ORIGINAL_INSTANCE_TIME].asLongOrNull() == null) {
            throw IllegalArgumentException("Exceptions must specify originalInstanceTime")
        }
        val parent = rows.getValue(Table.EVENTS)
            .firstOrNull { it[Events._ID].asLongOrNull() == parentEventId }
            ?: throw IllegalArgumentException("no parent event $parentEventId")

        val cloned = LinkedHashMap(parent)
        listOf("_sync_id", "sync_data1", "sync_data2", "sync_data3", "sync_data4", "sync_data5",
            "sync_data6", "sync_data7", "sync_data8", "sync_data9", "sync_data10")
            .forEach { cloned.remove(it) }
        cloned[Events.ORIGINAL_ID] = parentEventId
        cloned.putAll(values)
        cloned[Events._ID] = nextId++
        cloned[Events.DIRTY] = 1L
        rows.getValue(Table.EVENTS).add(cloned)
        return cloned[Events._ID] as Long
    }

    override fun insertBatch(table: Table, rows: List<Row>): List<Long?> {
        // applyBatch is transactional: one bad row rolls the whole batch back.
        val snapshot = this.rows.getValue(table).toMutableList()
        val ids = mutableListOf<Long?>()
        try {
            rows.forEach { ids.add(insertInternal(table, it)) }
        } catch (e: Exception) {
            this.rows[table] = snapshot
            throw e
        }
        return ids
    }

    private fun insertInternal(table: Table, values: Row): Long {
        writtenColumns += values.keys
        when (table) {
            Table.EVENTS -> validateEvent(values)
            Table.ATTENDEES -> requireNotNull(values[Attendees.EVENT_ID])
            Table.REMINDERS -> requireNotNull(values[Reminders.EVENT_ID])
            Table.EXTENDED_PROPERTIES -> throw IllegalArgumentException(
                "Only sync adapters may write using extended properties"
            )
            Table.COLORS -> throw IllegalArgumentException("Only sync adapters may write using colors")
            Table.CALENDARS -> Unit
        }
        val stored = LinkedHashMap(values)
        stored[Events._ID] = nextId++
        if (table == Table.EVENTS) {
            // CalendarProvider2.insertInTransactionInner: ordinary writes are marked dirty.
            stored[Events.DIRTY] = 1L
            stored[Events.LAST_DATE] =
                if (stored[Events.RRULE].asNonEmptyString() == null) stored[Events.DTSTART] else null
        }
        rows.getValue(table).add(stored)
        return stored[Events._ID] as Long
    }

    /** CalendarProvider2.verifyColumns / verifyNoSyncColumns / validateEventData. */
    private fun validateEvent(values: Row) {
        ColumnPolicy.PROVIDER_OWNED_EVENT_COLUMNS.forEach { column ->
            if (values.containsKey(column)) {
                throw IllegalArgumentException("Only the provider may write to $column")
            }
        }
        ColumnPolicy.SYNC_ONLY_EVENT_COLUMNS.forEach { column ->
            if (values.containsKey(column)) {
                throw IllegalArgumentException("Only sync adapters may write to $column")
            }
        }
        if (values[Events.CALENDAR_ID].asLongOrNull() == null) {
            throw IllegalArgumentException("Event values must include a calendar_id")
        }
        if (values[Events.EVENT_TIMEZONE].asNonEmptyString() == null) {
            throw IllegalArgumentException("Event values must include an eventTimezone")
        }
        val hasDtstart = values[Events.DTSTART].asLongOrNull() != null
        val hasDtend = values[Events.DTEND].asLongOrNull() != null
        val hasDuration = values[Events.DURATION].asNonEmptyString() != null
        if (!hasDtstart) throw IllegalArgumentException("DTSTART cannot be empty.")
        if (!hasDuration && !hasDtend) {
            throw IllegalArgumentException("DTEND and DURATION cannot both be null for an event.")
        }
        if (hasDuration && hasDtend) {
            throw IllegalArgumentException("Cannot have both DTEND and DURATION in an event")
        }
        values[Events.RRULE].asNonEmptyString()?.split("\n")?.forEach { rule ->
            if (rule.isNotBlank() && !rule.contains("FREQ=")) {
                throw IllegalArgumentException("Invalid recurrence rule: $rule")
            }
        }
        values[Events.EXRULE].asNonEmptyString()?.split("\n")?.forEach { rule ->
            if (rule.isNotBlank() && !rule.contains("FREQ=")) {
                throw IllegalArgumentException("Invalid recurrence rule: $rule")
            }
        }
    }

    // ------------------------------------------------------------- helpers

    fun addCalendar(id: Long, displayName: String, accountName: String, accountType: String): Row {
        val row = linkedMapOf<String, Any?>(
            Calendars._ID to id,
            Calendars.DISPLAY_NAME to displayName,
            Calendars.ACCOUNT_NAME to accountName,
            Calendars.ACCOUNT_TYPE to accountType,
            Calendars.ACCESS_LEVEL to 700L,
            "visible" to 1L,
            Calendars.CALENDAR_COLOR to 0xFF2F80C7.toInt().toLong(),
            Calendars.DELETED to 0L
        )
        rows.getValue(Table.CALENDARS).add(row)
        return row
    }

    fun addEvent(
        id: Long,
        calendarId: Long,
        title: String,
        dtstart: Long,
        dtend: Long?,
        rrule: String? = null,
        duration: String? = null,
        originalId: Long? = null,
        originalInstanceTime: Long? = null
    ): Row {
        val row = linkedMapOf<String, Any?>(
            Events._ID to id,
            Events.CALENDAR_ID to calendarId,
            Events.TITLE to title,
            Events.DTSTART to dtstart,
            Events.DTEND to dtend,
            Events.DURATION to duration,
            Events.EVENT_TIMEZONE to "Europe/Berlin",
            Events.ALL_DAY to 0L,
            Events.RRULE to rrule,
            Events.ORIGINAL_ID to originalId,
            Events.ORIGINAL_INSTANCE_TIME to originalInstanceTime,
            Events.STATUS to 1L,
            Events.AVAILABILITY to 0L,
            Events.ACCESS_LEVEL to 0L,
            Events.DELETED to 0L,
            Events.DIRTY to 0L,
            Events.LAST_SYNCED to 0L,
            Events._SYNC_ID to "src-$id",
            Events.SYNC_DATA1 to "adapter-private-$id",
            Events.UID_2445 to "uid-$id@example.test"
        )
        rows.getValue(Table.EVENTS).add(row)
        return row
    }

    fun addAttendee(eventId: Long, name: String, email: String, status: Long = 1L) {
        rows.getValue(Table.ATTENDEES).add(
            linkedMapOf(
                Attendees._ID to nextId++,
                Attendees.EVENT_ID to eventId,
                Attendees.NAME to name,
                Attendees.EMAIL to email,
                Attendees.STATUS to status,
                Attendees.TYPE to 1L,
                Attendees.RELATIONSHIP to 1L,
                Attendees.IDENTITY to "identity@example.test",
                Attendees.ID_NAMESPACE to "src-namespace"
            )
        )
    }

    fun addReminder(eventId: Long, minutes: Long, method: Long = 1L) {
        rows.getValue(Table.REMINDERS).add(
            linkedMapOf(
                Reminders._ID to nextId++,
                Reminders.EVENT_ID to eventId,
                Reminders.MINUTES to minutes,
                Reminders.METHOD to method
            )
        )
    }
}
