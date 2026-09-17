package dev.calendar.exportimport

/**
 * Replays a [Backup] into one existing, writable calendar.
 *
 * Scope is deliberately "case 1": an account and a calendar that already exist. Nothing here
 * creates a calendar or an account, and the sync-adapter query parameter is never used, so the
 * provider treats every write as an ordinary app write and marks inserted events dirty, which
 * is what makes the calendar's own sync adapter upload them.
 */
class Importer(
    private val gateway: ProviderGateway,
    private val log: (String) -> Unit = {}
) {

    private class Plan(val source: Row, val values: Row, val uid: String)

    fun import(
        backup: Backup,
        destinationCalendarId: Long,
        sourceCalendarIds: Set<Long>,
        skipExisting: Boolean
    ): ImportReport {
        val report = ImportReport()

        val sourceEvents = backup.rows(Table.EVENTS).filter { row ->
            row[Events.DELETED].asLongOrNull() != 1L &&
                row[Events.CALENDAR_ID].asLongOrNull() in sourceCalendarIds
        }
        report.considered = sourceEvents.size

        val existingUids = if (skipExisting) {
            existingUids(destinationCalendarId)
        } else {
            emptySet()
        }

        val normal = mutableListOf<Plan>()
        val overrides = mutableListOf<Plan>()
        sourceEvents.forEach { row ->
            val uid = (row[ImportKeys.COLUMN] as? String)?.takeIf { it.isNotEmpty() }
                ?: ImportKeys.uidFor(row)
            if (uid in existingUids) {
                report.skipped++
                return@forEach
            }
            val isOverride = EventRules.isException(row)
            try {
                if (isOverride) {
                    overrides.add(Plan(row, EventRules.buildException(row, uid), uid))
                } else {
                    val values = EventRules.buildEvent(row, uid)
                    values[Events.CALENDAR_ID] = destinationCalendarId
                    normal.add(Plan(row, values, uid))
                }
            } catch (e: Exception) {
                report.failed++
                report.problem("event ${row[Events._ID]} rejected: ${e.message}")
            }
        }

        if (report.skipped > 0) {
            log("Skipped ${report.skipped} event(s) already present in the destination calendar.")
        }

        // Parents first: an override needs the new id of its series.
        insertEvents(normal, report)
        insertOverrides(overrides, destinationCalendarId, report)
        insertChildren(backup, report)
        return report
    }

    private fun existingUids(destinationCalendarId: Long): Set<String> =
        gateway.query(
            Table.EVENTS,
            "${Events.CALENDAR_ID}=?",
            arrayOf(destinationCalendarId.toString())
        ).mapNotNull { it[Events.UID_2445].asNonEmptyString() }.toSet()

    /**
     * Batches inserts for speed. A batch the provider rejects as a whole (one bad row aborts
     * the transaction) is retried row by row, so only the offending rows are lost and every
     * loss is reported.
     */
    private fun insertEvents(plans: List<Plan>, report: ImportReport) {
        plans.chunked(BATCH).forEach { chunk ->
            val ids = try {
                gateway.insertBatch(Table.EVENTS, chunk.map { it.values })
            } catch (batchFailure: Exception) {
                log("Batch of ${chunk.size} rejected (${batchFailure.message}); retrying row by row.")
                chunk.map { plan ->
                    try {
                        gateway.insert(Table.EVENTS, plan.values)
                    } catch (e: Exception) {
                        report.problem("event ${plan.source[Events._ID]} rejected: ${e.message}")
                        null
                    }
                }
            }
            record(ids, chunk, report)
        }
    }

    /**
     * Overrides go through the provider's exception URI under their new parent id, because
     * that path clones the parent and accepts only a whitelist of columns. If the series was
     * not part of this run the edit is kept as a standalone event rather than dropped, and the
     * deviation is always reported.
     */
    private fun insertOverrides(plans: List<Plan>, destinationCalendarId: Long, report: ImportReport) {
        plans.forEach { plan ->
            val parentOld = plan.source[Events.ORIGINAL_ID].asLongOrNull()
            val parentNew = parentOld?.let { report.idMap[it] }
            try {
                if (parentNew != null) {
                    val newId = gateway.insertException(parentNew, plan.values)
                    report.inserted++
                    report.exceptionsInserted++
                    plan.source[Events._ID].asLongOrNull()?.let { report.idMap[it] = newId }
                } else {
                    val values = EventRules.buildEvent(plan.source, plan.uid)
                    values[Events.CALENDAR_ID] = destinationCalendarId
                    val newId = gateway.insert(Table.EVENTS, values)
                    report.inserted++
                    plan.source[Events._ID].asLongOrNull()?.let { report.idMap[it] = newId }
                    report.problem(
                        "override of event $parentOld imported as a standalone event (new id " +
                            "$newId) because its series was not selected"
                    )
                }
            } catch (e: Exception) {
                report.failed++
                report.problem("override of event $parentOld rejected: ${e.message}")
            }
        }
    }

    private fun insertChildren(backup: Backup, report: ImportReport) {
        if (report.idMap.isEmpty()) return

        report.attendeesInserted += insertChildrenOf(
            backup, Table.ATTENDEES, Attendees.EVENT_ID, ATTENDEE_COLUMNS, report
        )
        report.remindersInserted += insertChildrenOf(
            backup, Table.REMINDERS, Reminders.EVENT_ID, REMINDER_COLUMNS, report
        )
    }

    /**
     * Re-parents a table's child rows onto the new event ids. Rows whose event was not imported
     * are reported rather than dropped in silence.
     */
    private fun insertChildrenOf(
        backup: Backup,
        table: Table,
        eventIdColumn: String,
        columns: List<String>,
        report: ImportReport
    ): Int {
        var orphaned = 0
        val rows = backup.rows(table).mapNotNull { row ->
            val oldEventId = row[eventIdColumn].asLongOrNull()
            val newEventId = oldEventId?.let { report.idMap[it] }
            if (newEventId == null) {
                orphaned++
                return@mapNotNull null
            }
            linkedMapOf<String, Any?>(eventIdColumn to newEventId).apply {
                columns.forEach { column -> row[column]?.let { put(column, it) } }
            }
        }
        if (orphaned > 0) {
            report.problem(
                "${table.jsonName}: $orphaned row(s) skipped because their event was not imported"
            )
        }
        return insertBatchResilient(table, rows, report)
    }

    private fun insertBatchResilient(table: Table, rows: List<Row>, report: ImportReport): Int {
        var inserted = 0
        rows.chunked(BATCH).forEach { chunk ->
            val ids = try {
                gateway.insertBatch(table, chunk)
            } catch (batchFailure: Exception) {
                chunk.map { row ->
                    try {
                        gateway.insert(table, row)
                    } catch (e: Exception) {
                        report.problem("$table row rejected: ${e.message}")
                        null
                    }
                }
            }
            inserted += ids.count { it != null }
        }
        return inserted
    }

    private fun record(ids: List<Long?>, chunk: List<Plan>, report: ImportReport) {
        ids.forEachIndexed { index, newId ->
            val plan = chunk[index]
            if (newId == null) {
                report.failed++
                return@forEachIndexed
            }
            report.inserted++
            plan.source[Events._ID].asLongOrNull()?.let { report.idMap[it] = newId }
        }
    }

    companion object {
        const val BATCH = 100
        val ATTENDEE_COLUMNS = listOf(
            Attendees.NAME, Attendees.EMAIL, Attendees.RELATIONSHIP, Attendees.TYPE,
            Attendees.STATUS
        )
        val REMINDER_COLUMNS = listOf(Reminders.MINUTES, Reminders.METHOD)
    }
}
