package dev.calendar.exportimport

/**
 * The single seam between the export and import logic and the calendar provider. The Android
 * implementation lives in [AndroidGateway]; tests supply an in-memory fake that enforces the
 * same restrictions the real provider does.
 */
interface ProviderGateway {

    /** Writable calendars on this device, with the fields needed to label a picker. */
    fun writableCalendars(): List<WritableCalendar>

    /** Reads every column of every row matching [selection] (null means all rows). */
    fun query(table: Table, selection: String?, selectionArgs: Array<String>?): List<Row>

    /** Inserts one row and returns its new _id. */
    fun insert(table: Table, values: Row): Long

    /** Inserts a recurrence exception under [parentEventId] via the provider's exception URI. */
    fun insertException(parentEventId: Long, values: Row): Long

    /**
     * Inserts a batch of rows into [table] in one transaction and returns the new _id of each.
     * The whole batch fails if any row does, which is what makes the caller's row-by-row retry
     * meaningful.
     */
    fun insertBatch(table: Table, rows: List<Row>): List<Long?>
}

data class WritableCalendar(
    val id: Long,
    val accountName: String?,
    val accountType: String?,
    val displayName: String?
) {
    val label: String
        get() = buildString {
            append(displayName ?: "(unnamed calendar)")
            when {
                accountType.equals("LOCAL", ignoreCase = true) -> append("  [on this device]")
                accountName != null -> append("  [$accountName]")
            }
        }
}
