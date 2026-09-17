package dev.caltransfer.providerdump

import java.security.MessageDigest

/**
 * Stable per-event identity, used both to write UID_2445 and to make re-imports idempotent.
 * Prefers the event's own iCalendar UID and falls back to a hash of what identifies the event.
 */
object ImportKeys {

    const val COLUMN = "_importKey"

    fun uidFor(row: Row): String =
        row[Events.UID_2445].asNonEmptyString() ?: hashOf(row)

    private fun hashOf(row: Row): String {
        val basis = buildString {
            append(row[Events.TITLE] ?: "")
            append('|').append(row[Events.DTSTART] ?: "")
            append('|').append(row[Events.EVENT_TIMEZONE] ?: "")
            append('|').append(row[Events.ALL_DAY] ?: "")
            append('|').append(row[Events.DURATION] ?: row[Events.DTEND] ?: "")
        }
        return "ctp-" + sha256Hex(basis).take(32)
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        digest.forEach { sb.append("%02x".format(it)) }
        return sb.toString()
    }
}
