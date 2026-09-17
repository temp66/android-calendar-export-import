# Vendored AOSP sources

Copied verbatim from the Android Open Source Project so that the behaviour this repository
depends on can be checked against the implementation rather than against a blog post. Each file
keeps its original Apache-2.0 header.

| File | Why it is here |
|---|---|
| `CalendarContract.java` | The provider's public schema: every table and column the app reads and writes. `scripts/fields-table.py` parses this file, so `docs/field-table.md` cannot drift from the platform. |
| `CalendarProvider2.java` | The write path: which columns are refused, what `validateEventData` requires, how recurrence exceptions are created, and that `caller_is_syncadapter` is only a URI parameter. |
| `CalendarDatabaseHelper.java` | The SQLite schema: the nullability of the colour columns, the colour triggers, and the `Events` view that defines `displayColor`. |
| `SQLiteContentProvider.java` | Where `getIsCallerSyncAdapter` is defined — the reason an ordinary app can restore sync-owned columns at all. |

Source: [frameworks/base](https://android.googlesource.com/platform/frameworks/base) for
`CalendarContract.java`, [packages/providers/CalendarProvider](https://android.googlesource.com/platform/packages/providers/CalendarProvider)
for the rest, branch `main`, retrieved 2026-09-17.

Licensed under Apache-2.0; see the file headers and the repository's `LICENSE`.
