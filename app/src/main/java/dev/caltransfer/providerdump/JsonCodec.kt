package dev.caltransfer.providerdump

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON <-> [Backup] conversion. Uses org.json, which ships with the Android platform, so the
 * app has no third party runtime dependencies. Nothing here touches android.*, so the codec is
 * exercised directly by JVM unit tests.
 *
 * Values keep their type: null, Long, Double, String, and ByteArray as {"$b64": "..."}.
 * Null is meaningful and is never coerced to 0.
 */
object JsonCodec {

    private const val B64_KEY = "\$b64"

    fun encode(backup: Backup): String {
        val root = JSONObject()
        val manifest = JSONObject()
        backup.manifest.forEach { (k, v) -> manifest.put(k, encodeValue(v)) }
        root.put("manifest", manifest)
        backup.tables.forEach { (table, rows) ->
            val array = JSONArray()
            rows.forEach { row ->
                val obj = JSONObject()
                row.forEach { (column, value) -> obj.put(column, encodeValue(value)) }
                array.put(obj)
            }
            root.put(table.jsonName, array)
        }
        return root.toString(2)
    }

    fun decode(text: String): Backup {
        val root = JSONObject(text)
        val backup = Backup()
        root.optJSONObject("manifest")?.let { manifest ->
            manifest.keys().forEach { key -> backup.manifest[key] = decodeValue(manifest.get(key)) }
        }
        backup.tables.keys.toList().forEach { table ->
            val array = root.optJSONArray(table.jsonName) ?: return@forEach
            val rows = backup.rows(table)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val row = linkedMapOf<String, Any?>()
                obj.keys().forEach { key -> row[key] = decodeValue(obj.get(key)) }
                rows.add(row)
            }
        }
        return backup
    }

    fun schemaVersionOf(text: String): Int? =
        runCatching { JSONObject(text).optJSONObject("manifest")?.optInt("schemaVersion") }
            .getOrNull()

    private fun encodeValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is ByteArray -> JSONObject().put(B64_KEY, Base64.encode(value))
        is Long, is Int, is Double, is Float, is Boolean, is String -> value
        else -> value.toString()
    }

    private fun decodeValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val b64 = value.optString(B64_KEY, "")
            if (b64.isNotEmpty()) Base64.decode(b64) else value.toString()
        }
        is Int -> value.toLong()
        is Long -> value
        // The provider stores every numeric field as INTEGER, so an integral double is a Long.
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong() else value
        is Boolean -> if (value) 1L else 0L
        else -> value
    }
}

/**
 * Minimal Base64 so the codec behaves identically on the JVM and on API 23 without pulling in
 * android.util (a stub in unit tests) or java.util.Base64 (API 26+).
 */
internal object Base64 {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(
                if (b1 >= 0) ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)]
                else '='
            )
            sb.append(if (b2 >= 0) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.filter { it != '\n' && it != '\r' }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            if (c == '=') break
            val v = ALPHABET.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
