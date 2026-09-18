package dev.calendar.exportimport

/**
 * Turns a dumped Events row into something the provider will accept.
 *
 * The provider is strict on the write path (CalendarProvider2.validateEventData and
 * checkAllowedInException), so the rules encoded here are:
 *  - drop columns the provider owns or computes, colour keys, sync-only columns and the
 *    exporter's own bookkeeping column;
 *  - ensure eventTimezone and DTSTART exist, and exactly one of DTEND / DURATION;
 *  - for a recurrence exception, keep only the provider's whitelist of settable columns and
 *    synthesise DURATION when the source only had DTEND, because DTEND is not settable there.
 */
object EventRules {

    class Rejected(val reason: String) : Exception(reason)

    fun isException(row: Row): Boolean = row[Events.ORIGINAL_ID].asLongOrNull() != null

    /** Builds the insertable row for a normal (non exception) event. */
    fun buildEvent(source: Row, uid: String): Row {
        val values = scrub(source)
        values[Events.UID_2445] = uid
        normalize(values)
        return values
    }

    /** Builds the insertable row for a recurrence exception. */
    fun buildException(source: Row, uid: String): Row {
        val scrubbed = scrub(source)
        // dtend is deliberately not settable on an exception, so everything needed to derive
        // the duration has to be read before the whitelist filter below removes it.
        val dtstart = scrubbed[Events.DTSTART].asLongOrNull()
            ?: throw Rejected("event has no dtstart")
        val originalInstanceTime = scrubbed[Events.ORIGINAL_INSTANCE_TIME].asLongOrNull()
            ?: throw Rejected("exception has no originalInstanceTime")
        val explicitDuration = scrubbed[Events.DURATION].asNonEmptyString()
        val dtend = scrubbed[Events.DTEND].asLongOrNull()
        val allDay = (scrubbed[Events.ALL_DAY].asLongOrNull() ?: 0L) != 0L

        val values = LinkedHashMap<String, Any?>()
        scrubbed.forEach { (k, v) -> if (k in ColumnPolicy.ALLOWED_IN_EXCEPTION) values[k] = v }
        values[Events.UID_2445] = uid
        values[Events.DTSTART] = dtstart
        values[Events.ORIGINAL_INSTANCE_TIME] = originalInstanceTime
        if (values[Events.EVENT_TIMEZONE].asNonEmptyString() == null) {
            values[Events.EVENT_TIMEZONE] = "UTC"
        }
        // DTEND is not settable on an exception: the provider derives it from DURATION.
        values[Events.DURATION] = explicitDuration
            ?: durationString(
                (dtend ?: throw Rejected("event has neither duration nor dtend")) - dtstart,
                allDay
            )
        values.remove(Events.DTEND)
        return values
    }

    private fun scrub(source: Row): Row {
        val values = LinkedHashMap<String, Any?>()
        source.forEach { (column, value) ->
            if (column == ImportKeys.COLUMN) return@forEach
            if (column in ColumnPolicy.ALWAYS_DROP) return@forEach
            if (column in ColumnPolicy.PROVIDER_OWNED_EVENT_COLUMNS) return@forEach
            if (column in ColumnPolicy.NOT_WRITTEN_EVENT_COLUMNS) return@forEach
            if (column in ColumnPolicy.SYNC_ONLY_EVENT_COLUMNS) return@forEach
            if (column in ColumnPolicy.COLOR_KEY_COLUMNS) return@forEach
            if (column in ColumnPolicy.NOT_COPIED_AS_IS) return@forEach
            values[column] = value
        }
        return values
    }

    /** Enforces the provider's validateEventData preconditions in place. */
    private fun normalize(values: Row) {
        if (values[Events.EVENT_TIMEZONE].asNonEmptyString() == null) {
            values[Events.EVENT_TIMEZONE] = "UTC"
        }
        if (values[Events.DTSTART].asLongOrNull() == null) {
            throw Rejected("event has no dtstart")
        }
        val hasDuration = values[Events.DURATION].asNonEmptyString() != null
        val hasDtend = values[Events.DTEND].asLongOrNull() != null
        val recurring = values[Events.RRULE].asNonEmptyString() != null ||
            values[Events.RDATE].asNonEmptyString() != null
        when {
            hasDuration && hasDtend -> {
                // "Cannot have both DTEND and DURATION": keep whichever matches the shape.
                if (recurring) values.remove(Events.DTEND) else values.remove(Events.DURATION)
            }
            !hasDuration && !hasDtend -> throw Rejected("event has neither duration nor dtend")
        }
    }

    /** RFC 2445 duration, as the provider stores it. */
    fun durationString(millis: Long, allDay: Boolean): String {
        val seconds = (millis / 1000L).coerceAtLeast(0L)
        val days = seconds / 86_400L
        val rest = seconds % 86_400L
        return when {
            allDay && rest == 0L -> "P${days.coerceAtLeast(1L)}D"
            days == 0L -> "PT${seconds}S"
            rest == 0L -> "P${days}D"
            else -> "P${days}DT${rest}S"
        }
    }
}
