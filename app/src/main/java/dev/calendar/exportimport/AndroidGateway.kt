package dev.calendar.exportimport

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract

/**
 * The real provider. Nothing here passes caller_is_syncadapter: every write is an ordinary app
 * write, which the provider accepts for events, attendees and reminders and marks dirty so the
 * destination calendar's own sync adapter uploads them.
 */
class AndroidGateway(private val resolver: ContentResolver) : ProviderGateway {

    override fun writableCalendars(): List<WritableCalendar> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val selection = "(${CalendarContract.Calendars.DELETED}=0" +
            " OR ${CalendarContract.Calendars.DELETED} IS NULL)" +
            " AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?"
        val calendars = mutableListOf<WritableCalendar>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calendars += WritableCalendar(
                    id = cursor.getLong(0),
                    accountName = cursor.getString(1),
                    accountType = cursor.getString(2),
                    displayName = cursor.getString(3)
                )
            }
        }
        return calendars.sortedBy { it.label.lowercase() }
    }

    override fun query(table: Table, selection: String?, selectionArgs: Array<String>?): List<Row> {
        val rows = mutableListOf<Row>()
        resolver.query(uriOf(table), null, selection, selectionArgs, null)?.use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, Any?>(columns.size)
                columns.forEachIndexed { index, name -> row[name] = readValue(cursor, index) }
                rows.add(row)
            }
        }
        return rows
    }

    override fun insert(table: Table, values: Row): Long {
        val uri = resolver.insert(uriOf(table), toContentValues(values))
            ?: throw IllegalStateException("provider returned no uri inserting into $table")
        return ContentUris.parseId(uri)
    }

    override fun insertException(parentEventId: Long, values: Row): Long {
        val uri = ContentUris.withAppendedId(
            CalendarContract.Events.CONTENT_EXCEPTION_URI,
            parentEventId
        )
        val result = resolver.insert(uri, toContentValues(values))
            ?: throw IllegalStateException("provider returned no uri inserting an override")
        return ContentUris.parseId(result)
    }

    override fun insertBatch(table: Table, rows: List<Row>): List<Long?> {
        if (rows.isEmpty()) return emptyList()
        val uri = uriOf(table)
        val operations = ArrayList<ContentProviderOperation>(rows.size)
        rows.forEach { row ->
            operations.add(
                ContentProviderOperation.newInsert(uri).withValues(toContentValues(row)).build()
            )
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        return results.map { result -> result.uri?.let { ContentUris.parseId(it) } }
    }

    private fun uriOf(table: Table): Uri = when (table) {
        Table.CALENDARS -> CalendarContract.Calendars.CONTENT_URI
        Table.EVENTS -> CalendarContract.Events.CONTENT_URI
        Table.ATTENDEES -> CalendarContract.Attendees.CONTENT_URI
        Table.REMINDERS -> CalendarContract.Reminders.CONTENT_URI
        Table.EXTENDED_PROPERTIES -> CalendarContract.ExtendedProperties.CONTENT_URI
        Table.COLORS -> CalendarContract.Colors.CONTENT_URI
    }

    private fun readValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
        else -> cursor.getString(index)
    }

    private fun toContentValues(row: Row): ContentValues {
        val values = ContentValues(row.size)
        row.forEach { (column, value) ->
            when (value) {
                null -> values.putNull(column)
                is Long -> values.put(column, value)
                is Int -> values.put(column, value)
                is Double -> values.put(column, value)
                is Float -> values.put(column, value)
                is Boolean -> values.put(column, if (value) 1 else 0)
                is ByteArray -> values.put(column, value)
                else -> values.put(column, value.toString())
            }
        }
        return values
    }
}
