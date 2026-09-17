package dev.caltransfer.providerdump

/**
 * One provider row: column name -> value. Values are null, Long, Double, String or ByteArray.
 * Deliberately free of android.* types so the whole export/import/verify core is unit testable
 * on the JVM against a fake gateway.
 */
typealias Row = MutableMap<String, Any?>

enum class Table(val jsonName: String) {
    CALENDARS("calendars"),
    EVENTS("events"),
    ATTENDEES("attendees"),
    REMINDERS("reminders"),
    EXTENDED_PROPERTIES("extendedProperties"),
    COLORS("colors")
}

const val SCHEMA_VERSION = 1

/** Everything read off one device. */
class Backup(
    val manifest: MutableMap<String, Any?> = linkedMapOf(),
    val tables: MutableMap<Table, MutableList<Row>> =
        Table.entries.associateWith { mutableListOf<Row>() }.toMutableMap()
) {
    fun rows(table: Table): MutableList<Row> = tables.getOrPut(table) { mutableListOf() }
}

/** Result of one import run. */
class ImportReport {
    var considered = 0
    var inserted = 0
    var skipped = 0
    var failed = 0
    var exceptionsInserted = 0
    var attendeesInserted = 0
    var remindersInserted = 0
    val problems = mutableListOf<String>()
    /** old event _id -> new event _id, for inserted events. */
    val idMap = mutableMapOf<Long, Long>()

    fun problem(message: String) {
        if (problems.size < MAX_PROBLEMS) problems.add(message)
        else if (problems.size == MAX_PROBLEMS) problems.add("(further problems suppressed)")
    }

    companion object {
        const val MAX_PROBLEMS = 200
    }
}

fun Any?.asLongOrNull(): Long? = when (this) {
    null -> null
    is Long -> this
    is Int -> this.toLong()
    is Short -> this.toLong()
    is Double -> this.toLong()
    is Float -> this.toLong()
    is String -> this.trim().toLongOrNull()
    else -> null
}

fun Any?.asNonEmptyString(): String? = (this as? String)?.takeIf { it.isNotEmpty() }
