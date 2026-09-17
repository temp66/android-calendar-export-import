package dev.calendar.exportimport

/**
 * Reads the whole provider into a [Backup]. Queries are unrestricted on the provider side, so
 * every column of every row is captured as-is; the only filter applied is dropping
 * lastSynced=1 pre-edit copies, which are transient duplicates rather than data.
 */
class Exporter(
    private val gateway: ProviderGateway,
    private val appVersion: String,
    private val device: () -> Map<String, Any?>
) {

    fun export(): Backup {
        val backup = Backup()
        backup.manifest["schemaVersion"] = SCHEMA_VERSION.toLong()
        backup.manifest["appVersion"] = appVersion
        backup.manifest["exportedAt"] = System.currentTimeMillis()
        device().forEach { (k, v) -> backup.manifest[k] = v }

        Table.entries.forEach { table ->
            val rows = gateway.query(table, selectionFor(table), null)
            val destination = backup.rows(table)
            rows.forEach { row ->
                val copy = LinkedHashMap<String, Any?>(row)
                if (table == Table.EVENTS) copy[ImportKeys.COLUMN] = ImportKeys.uidFor(row)
                destination.add(copy)
            }
            backup.manifest["count.${table.jsonName}"] = destination.size.toLong()
        }
        return backup
    }

    private fun selectionFor(table: Table): String? = when (table) {
        Table.EVENTS -> "${Events.LAST_SYNCED} IS NULL OR ${Events.LAST_SYNCED}=0"
        else -> null
    }
}
