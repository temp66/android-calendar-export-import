package dev.calendar.exportimport

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two things the rest of the project assumes but cannot enforce by types: that every
 * column named in [ColumnPolicy] really exists in the provider schema, and that the app never
 * reaches for the sync-adapter query parameter.
 */
class PolicyTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repository root from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `every column named in the policy exists in the provider schema`() {
        val schema = File(repoRoot(), "third_party/aosp/CalendarContract.java")
        assertTrue("missing ${schema.path}", schema.isFile)
        val declared = Regex("""public static final String\s+[A-Z0-9_]+\s*=\s*"([^"]+)"""")
            .findAll(schema.readText())
            .map { it.groupValues[1] }
            .toSet() + "_id" // BaseColumns._ID, inherited rather than redeclared by CalendarContract

        val policy = File(repoRoot(), "app/src/main/java/dev/calendar/exportimport/ColumnPolicy.kt")
        assertTrue("missing ${policy.path}", policy.isFile)
        val referenced = Regex("""^\s*val ([A-Z_]+) = (?:listOf|setOf)\(\s*([^)]*)\)""", RegexOption.MULTILINE)
            .findAll(policy.readText())
            .associate { match ->
                match.groupValues[1] to Regex("\"([^\"]+)\"").findAll(match.groupValues[2])
                    .map { it.groupValues[1] }.toList()
            }
        assertTrue("no policy sets were parsed", referenced.containsKey("PROVIDER_OWNED_EVENT_COLUMNS"))

        val unknown = referenced.flatMap { (name, columns) ->
            columns.filterNot { it in declared }.map { "$name names unknown column '$it'" }
        }
        assertTrue(unknown.joinToString("\n"), unknown.isEmpty())
    }

    @Test
    fun `the sync adapter parameter is never used`() {
        // Using it would mean naming CalendarContract.CALLER_IS_SYNCADAPTER, so the constant is
        // the thing to look for; prose mentions of the parameter (as in AndroidGateway's KDoc)
        // are fine.
        val production = File(repoRoot(), "app/src/main/java")
        val offenders = production.walkTopDown()
            .filter { it.isFile }
            .filter { "CALLER_IS_SYNCADAPTER" in it.readText() }
            .map { it.relativeTo(repoRoot()).path }
            .toList()
        assertFalse("these files use the sync adapter parameter: $offenders", offenders.isNotEmpty())
    }

    @Test
    fun `every compared field is settable on a recurrence override`() {
        // An override may only carry ColumnPolicy.ALLOWED_IN_EXCEPTION, so a field the verifier
        // compares but the provider refuses there would be dropped for overrides alone - the kind
        // of gap that would only show up on one edited instance of a series.
        val droppedOnOverrides = ColumnPolicy.VERIFY_EVENT_FIELDS
            .filterNot { it in ColumnPolicy.ALLOWED_IN_EXCEPTION }
            .filterNot { it == Events.DTEND } // derived from DURATION by the provider
        assertTrue(
            "compared but not settable on an override: $droppedOnOverrides",
            droppedOnOverrides.isEmpty()
        )
    }
}
