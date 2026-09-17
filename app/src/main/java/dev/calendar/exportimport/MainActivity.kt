package dev.calendar.exportimport

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var destinationSpinner: Spinner
    private lateinit var sourceContainer: LinearLayout
    private lateinit var skipExisting: CheckBox
    private lateinit var startImport: Button
    private lateinit var saveReport: Button
    private lateinit var verifyButton: Button
    private lateinit var exportButton: Button
    private lateinit var importButton: Button
    private lateinit var grantButton: Button

    private var calendars: List<WritableCalendar> = emptyList()
    private var loaded: Backup? = null
    private var lastReport: String? = null
    private val sourceChecks = mutableMapOf<Long, CheckBox>()
    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshPermissionState()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): ScrollView {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(heading(getString(R.string.app_name)))
        column.addView(label(getString(R.string.intro)))

        statusView = label("")
        column.addView(statusView)
        grantButton = button(getString(R.string.grant_access)) { requestCalendarPermissions() }
        column.addView(grantButton)

        column.addView(heading(getString(R.string.section_export)))
        exportButton = button(getString(R.string.export_to_file)) { startExport() }
        column.addView(exportButton)

        column.addView(heading(getString(R.string.section_import)))
        importButton = button(getString(R.string.choose_backup)) { pickFile(REQ_IMPORT) }
        column.addView(importButton)

        column.addView(label(getString(R.string.destination_label)))
        destinationSpinner = Spinner(this)
        column.addView(destinationSpinner)

        skipExisting = CheckBox(this).apply {
            text = getString(R.string.skip_existing)
            isChecked = true
        }
        column.addView(skipExisting)

        column.addView(label(getString(R.string.source_label)))
        sourceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sourceContainer)

        startImport = button(getString(R.string.start_import)) { startImport() }
        startImport.isEnabled = false
        column.addView(startImport)

        column.addView(heading(getString(R.string.section_verify)))
        verifyButton = button(getString(R.string.verify_backup)) { pickFile(REQ_VERIFY) }
        column.addView(verifyButton)

        saveReport = button(getString(R.string.save_report)) { saveLastReport() }
        saveReport.isEnabled = false
        column.addView(saveReport)

        column.addView(heading(getString(R.string.section_log)))
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        column.addView(
            logView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return ScrollView(this).apply { addView(column) }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setPadding(0, (resources.displayMetrics.density * 14).toInt(), 0, 4)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 4, 0, 4)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    /** Diagnostic output, deliberately not translated: see res/values/strings.xml. */
    @SuppressLint("SetTextI18n")
    private fun log(text: String) {
        runOnUiThread {
            logLines.addLast(text)
            while (logLines.size > 300) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
        }
    }

    // ------------------------------------------------------- permissions

    private fun refreshPermissionState() {
        val read = checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
        val write = checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)
        val granted = read == PackageManager.PERMISSION_GRANTED &&
            write == PackageManager.PERMISSION_GRANTED
        statusView.text = getString(
            if (granted) R.string.status_granted else R.string.status_not_granted
        )
        grantButton.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) refreshCalendars()
    }

    private fun requestCalendarPermissions() {
        requestPermissions(
            arrayOf(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR
            ),
            REQ_PERMISSIONS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionState()
    }

    private fun refreshCalendars() {
        try {
            calendars = AndroidGateway(contentResolver).writableCalendars()
            destinationSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                calendars.map { it.label }
            )
            if (calendars.isEmpty()) {
                log(getString(R.string.no_writable_calendar))
            }
        } catch (e: Exception) {
            log("Could not list calendars: ${e.message}")
        }
    }

    // ------------------------------------------------------------ export

    private fun startExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "calendar-dump-$stamp.json")
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    private fun runExport(target: Uri) {
        log("Exporting...")
        Thread {
            try {
                val gateway = AndroidGateway(contentResolver)
                val backup = Exporter(gateway, packageVersion(), ::deviceInfo).export()
                val text = JsonCodec.encode(backup)
                contentResolver.openOutputStream(target)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("could not open the chosen file for writing")
                backup.tables.forEach { (table, rows) ->
                    log("  ${table.jsonName}: ${rows.size}")
                }
                log("Export finished, ${text.length} characters written.")
            } catch (e: Exception) {
                log("Export failed: ${e.message}")
            }
        }.start()
    }

    private fun packageVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun deviceInfo(): Map<String, Any?> = mapOf(
        "sdkInt" to Build.VERSION.SDK_INT.toLong(),
        "androidRelease" to Build.VERSION.RELEASE,
        "model" to Build.MODEL,
        "manufacturer" to Build.MANUFACTURER,
        "packageName" to packageName
    )

    // ------------------------------------------------------------ import

    private fun pickFile(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, requestCode)
    }

    private fun loadBackup(uri: Uri, then: (Backup) -> Unit) {
        log("Reading backup...")
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: throw IllegalStateException("could not open the chosen file")
                val version = JsonCodec.schemaVersionOf(text)
                if (version == null || version > SCHEMA_VERSION) {
                    runOnUiThread {
                        log(
                            getString(
                                R.string.unsupported_schema,
                                version?.toString() ?: "missing",
                                SCHEMA_VERSION
                            )
                        )
                    }
                    return@Thread
                }
                val backup = JsonCodec.decode(text)
                runOnUiThread {
                    loaded = backup
                    val counts = backup.tables.entries
                        .joinToString(", ") { "${it.key.jsonName}=${it.value.size}" }
                    log("Loaded backup: $counts")
                    if (backup.rows(Table.EVENTS).isEmpty()) {
                        log(getString(R.string.empty_backup))
                    }
                    then(backup)
                }
            } catch (e: Exception) {
                runOnUiThread { log("Could not read the backup: ${e.message}") }
            }
        }.start()
    }

    private fun showSourceCalendars(backup: Backup) {
        sourceChecks.clear()
        sourceContainer.removeAllViews()
        val sourceCalendars = backup.rows(Table.CALENDARS).filter {
            it[Calendars.DELETED].asLongOrNull() != 1L
        }
        if (sourceCalendars.isEmpty()) {
            sourceContainer.addView(label(getString(R.string.no_calendars_in_backup)))
            return
        }
        sourceCalendars.forEach { row ->
            val id = row[Calendars._ID].asLongOrNull() ?: return@forEach
            val box = CheckBox(this).apply {
                text = row[Calendars.DISPLAY_NAME].asNonEmptyString()
                    ?: "${getString(R.string.unnamed_calendar)} #$id"
                isChecked = true
            }
            sourceChecks[id] = box
            sourceContainer.addView(box)
        }
    }

    private fun startImport() {
        val backup = loaded ?: return
        val destination = calendars.getOrNull(destinationSpinner.selectedItemPosition)
        if (destination == null) {
            Toast.makeText(this, R.string.need_destination, Toast.LENGTH_LONG).show()
            return
        }
        val sources = sourceChecks.filterValues { it.isChecked }.keys
        if (sources.isEmpty()) {
            Toast.makeText(this, R.string.need_source, Toast.LENGTH_LONG).show()
            return
        }
        startImport.isEnabled = false
        log("Importing into \"${destination.label}\"...")
        Thread {
            try {
                val gateway = AndroidGateway(contentResolver)
                val report = Importer(gateway, ::log).import(
                    backup,
                    destination.id,
                    sources,
                    skipExisting.isChecked
                )
                log(
                    """
                    Import finished.
                      considered:  ${report.considered}
                      inserted:    ${report.inserted} (overrides ${report.exceptionsInserted})
                      skipped:     ${report.skipped}
                      failed:      ${report.failed}
                      attendees:   ${report.attendeesInserted}
                      reminders:   ${report.remindersInserted}
                    """.trimIndent()
                )
                report.problems.forEach { log("  note: $it") }
                log("Run Verify next to compare the destination against the backup.")
            } catch (e: Exception) {
                log("Import failed: ${e.message}")
            } finally {
                runOnUiThread { startImport.isEnabled = true }
            }
        }.start()
    }

    // ------------------------------------------------------------ verify

    private fun runVerify(backup: Backup) {
        val destination = calendars.getOrNull(destinationSpinner.selectedItemPosition)
        if (destination == null) {
            Toast.makeText(this, R.string.need_destination, Toast.LENGTH_LONG).show()
            return
        }
        val sources = sourceChecks.filterValues { it.isChecked }.keys.ifEmpty {
            backup.rows(Table.CALENDARS).mapNotNull { it[Calendars._ID].asLongOrNull() }.toSet()
        }
        log("Verifying against \"${destination.label}\"...")
        Thread {
            try {
                val report = Verifier(AndroidGateway(contentResolver))
                    .verify(backup, destination.id, sources)
                lastReport = report
                report.lines().forEach { log(it) }
                runOnUiThread { saveReport.isEnabled = true }
            } catch (e: Exception) {
                log("Verify failed: ${e.message}")
            }
        }.start()
    }

    private fun saveLastReport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "calendar-verify-report.txt")
        }
        startActivityForResult(intent, REQ_SAVE_REPORT)
    }

    // ----------------------------------------------------- result plumbing

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> runExport(uri)
            REQ_IMPORT -> loadBackup(uri) { backup ->
                showSourceCalendars(backup)
                startImport.isEnabled = true
                log("Choose the destination calendar and press Start import.")
            }
            REQ_VERIFY -> loadBackup(uri) { backup ->
                if (sourceChecks.isEmpty()) showSourceCalendars(backup)
                runVerify(backup)
            }
            REQ_SAVE_REPORT -> {
                val report = lastReport ?: return
                Thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(report.toByteArray(Charsets.UTF_8))
                        }
                        log("Report saved.")
                    } catch (e: Exception) {
                        log("Could not save the report: ${e.message}")
                    }
                }.start()
            }
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_EXPORT = 2
        private const val REQ_IMPORT = 3
        private const val REQ_VERIFY = 4
        private const val REQ_SAVE_REPORT = 5
    }
}
