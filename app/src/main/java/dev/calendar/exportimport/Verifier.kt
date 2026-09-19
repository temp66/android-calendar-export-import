package dev.calendar.exportimport

/**
 * Field level comparison of what a backup says should be in a destination calendar against
 * what is actually there.
 *
 * Only the fields the write path can preserve are compared; everything else is printed under
 * "not checked" with the reason, so an expected loss never looks like a bug and a real one is
 * never hidden.
 */
class Verifier(private val gateway: ProviderGateway) {

    private class Expected(
        val uid: String,
        val sourceId: Long?,
        val values: Row,
        val fields: List<String>
    )

    fun verify(
        backup: Backup,
        destinationCalendarId: Long,
        sourceCalendarIds: Set<Long>,
        maxDetails: Int = 40
    ): String {
        val expected = backup.rows(Table.EVENTS)
            .filter { row ->
                row[Events.DELETED].asLongOrNull() != 1L &&
                    row[Events.CALENDAR_ID].asLongOrNull() in sourceCalendarIds
            }
            .mapNotNull { row ->
                val uid = (row[ImportKeys.COLUMN] as? String)?.takeIf { it.isNotEmpty() }
                    ?: ImportKeys.uidFor(row)
                val override = EventRules.isException(row)
                runCatching {
                    // Compare exactly what the importer intended to write, and for an override
                    // only the columns the provider lets an exception set; the rest are
                    // inherited from the series by the provider itself.
                    Expected(
                        uid = uid,
                        sourceId = row[Events._ID].asLongOrNull(),
                        values = if (override) EventRules.buildException(row, uid)
                        else EventRules.buildEvent(row, uid),
                        fields = if (override) {
                            ColumnPolicy.VERIFY_EVENT_FIELDS.filter {
                                it in ColumnPolicy.ALLOWED_IN_EXCEPTION
                            }
                        } else {
                            ColumnPolicy.VERIFY_EVENT_FIELDS
                        }
                    )
                }.getOrNull()
            }

        val actual = gateway.query(
            Table.EVENTS,
            "${Events.CALENDAR_ID}=?",
            arrayOf(destinationCalendarId.toString())
        ).mapNotNull { row -> row[Events.UID_2445].asNonEmptyString()?.let { it to row } }.toMap()

        val backupAttendees = groupByEvent(backup.rows(Table.ATTENDEES), Attendees.EVENT_ID)
        val backupReminders = groupByEvent(backup.rows(Table.REMINDERS), Reminders.EVENT_ID)
        val hostAttendees = groupByEvent(gateway.query(Table.ATTENDEES, null, null), Attendees.EVENT_ID)
        val hostReminders = groupByEvent(gateway.query(Table.REMINDERS, null, null), Reminders.EVENT_ID)

        val missing = mutableListOf<String>()
        val mismatches = mutableListOf<String>()
        var eventsWithDifferences = 0

        expected.forEach { want ->
            val got = actual[want.uid]
            if (got == null) {
                val title = want.values[Events.TITLE] ?: "(untitled)"
                val start = want.values[Events.DTSTART]
                missing.add("${want.uid}  $title  dtstart=$start")
                return@forEach
            }
            val differences = mutableListOf<String>()
            want.fields.forEach { field ->
                if (!sameValue(want.values[field], got[field])) {
                    differences.add("$field: ${render(want.values[field])} -> ${render(got[field])}")
                }
            }
            val hostEventId = got[Events._ID].asLongOrNull()
            if (hostEventId != null) {
                differences += compareChildren(
                    "attendees",
                    (backupAttendees[want.sourceId] ?: emptyList()).map { attendeeSignature(it) },
                    (hostAttendees[hostEventId] ?: emptyList()).map { attendeeSignature(it) }
                )
                differences += compareChildren(
                    "reminders",
                    (backupReminders[want.sourceId] ?: emptyList()).map { reminderSignature(it) },
                    (hostReminders[hostEventId] ?: emptyList()).map { reminderSignature(it) }
                )
            }
            if (differences.isNotEmpty()) {
                eventsWithDifferences++
                mismatches.add("${want.values[Events.TITLE] ?: "(untitled)"}  [${want.uid}]")
                differences.take(maxDetails).forEach { mismatches.add("    $it") }
                if (differences.size > maxDetails) {
                    mismatches.add("    (${differences.size - maxDetails} more)")
                }
            }
        }

        return buildString {
            appendLine("=== Verification ===")
            appendLine("Events expected in destination:  ${expected.size}")
            appendLine("Events matched by UID:           ${expected.size - missing.size}")
            appendLine("Missing:                         ${missing.size}")
            appendLine("Events with field differences:   $eventsWithDifferences")
            appendLine()
            if (missing.isNotEmpty()) {
                appendLine("-- Missing events --")
                missing.take(maxDetails).forEach { appendLine("  $it") }
                if (missing.size > maxDetails) appendLine("  (${missing.size - maxDetails} more)")
                appendLine()
            }
            if (mismatches.isNotEmpty()) {
                appendLine("-- Field differences --")
                mismatches.forEach { appendLine("  $it") }
                appendLine()
            }
            if (missing.isEmpty() && mismatches.isEmpty()) {
                appendLine("No differences in the fields this import can preserve.")
                appendLine()
            }
            appendLine("-- Not compared, and why --")
            ColumnPolicy.EXPECTED_LOSS.forEach { (field, reason) ->
                appendLine("  $field")
                appendLine("      $reason")
            }
            appendLine()
            appendLine("An account's sync adapter may rewrite uid2445, eventColor and the guestsCan*")
            appendLine("flags after upload, so run verify before the first sync for the cleanest result.")
        }
    }

    private fun groupByEvent(rows: List<Row>, eventIdColumn: String): Map<Long, List<Row>> =
        rows.mapNotNull { row -> row[eventIdColumn].asLongOrNull()?.let { it to row } }
            .groupBy({ it.first }, { it.second })

    private fun compareChildren(name: String, want: List<String>, got: List<String>): List<String> =
        if (want.sorted() == got.sorted()) {
            emptyList()
        } else {
            listOf("$name: expected ${want.size} ${want.sorted()} but found ${got.size} ${got.sorted()}")
        }

    private fun attendeeSignature(row: Row): String =
        signature(row, ColumnPolicy.VERIFY_ATTENDEE_FIELDS)

    private fun reminderSignature(row: Row): String =
        signature(row, ColumnPolicy.VERIFY_REMINDER_FIELDS)

    /** Set comparison for child rows; absent text is spelled the same way whatever form it takes. */
    private fun signature(row: Row, fields: List<String>): String =
        fields.joinToString("|") { field ->
            val value = row[field]
            if (isAbsentText(value)) NULL_TEXT else render(value)
        }

    private fun sameValue(a: Any?, b: Any?): Boolean {
        // The provider stores an absent text column as "", so a value written as null reads back
        // as an empty string. Treating those as different would report every copied event whose
        // description, location or custom-app link was absent as a mismatch.
        if (isAbsentText(a) && isAbsentText(b)) return true
        val la = a.asLongOrNull()
        val lb = b.asLongOrNull()
        if (la != null && lb != null) return la == lb
        return a == b
    }

    private fun isAbsentText(value: Any?): Boolean = value == null || value == ""

    private fun render(value: Any?): String = when (value) {
        null -> NULL_TEXT
        is ByteArray -> "<${value.size} bytes>"
        else -> value.toString()
    }

    private companion object {
        const val NULL_TEXT = "null"
    }
}
