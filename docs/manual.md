# Manual

How the app behaves, what it preserves and what it deliberately does not. Requirements and build
instructions are in the [repository README](../README.md); the per-field table is in
[field-table.md](field-table.md), and the ICS comparison that motivated this tool is in
[ics-loss-analysis.md](ics-loss-analysis.md).

## What it does

**On the source device** it dumps every row of the six data tables behind `com.android.calendar`
— `Calendars`, `Events`, `Attendees`, `Reminders`, `ExtendedProperties`, `Colors` — with every
column the provider returns, into one JSON file. Queries are unrestricted on the provider side;
the only thing filtered out is `lastSynced=1`, the transient pre-edit copies the provider keeps
for partially updatable calendars.

**On the target device** it replays the events, their recurrence overrides, their attendees and
their reminders into a calendar that **already exists**. It never creates a calendar and never
creates an account, and it never uses the `caller_is_syncadapter` query parameter, so every
write is an ordinary app write. The provider marks each inserted event `DIRTY=1`, which is
exactly what makes the calendar's own sync adapter (Google's, or whichever account owns the
destination calendar) upload it. The result is events that look as if they had been created in
that calendar in the first place.

## Why only "existing account, existing calendar"

There are four ways data can land on the target, and only the first is implemented:

| # | Situation | What it needs | In this app |
|---|---|---|---|
| 1 | An account and one of its calendars already exist | `WRITE_CALENDAR` | **yes** |
| 2 | An account exists but has no calendar | creating a calendar, which the provider only allows with the sync-adapter parameter; for a Google account the adapter may then remove a calendar it does not know about | no |
| 3 | No account at all | registering an `AccountAuthenticator` (the ICSx⁵ model) plus a calendar | no |
| 4 | A device-local calendar | same as case 2 with `account_type=LOCAL` | no |

Case 1 is also what makes "local" versus "Google" stop being a mode: the picker lists every
writable calendar on the device, and a device-local calendar is simply one of the entries. If
the target account has no calendar yet, create one in your calendar app first (two taps) and
reopen the screen.

## Installing

```
adb install -r outputs/calendar-transporter-debug.apk
```

The app needs `READ_CALENDAR` and `WRITE_CALENDAR`; it asks for them on first launch. There are
no storage permissions: files are chosen through the system file picker.

## Using it

On the **source** device:

1. Grant calendar access.
2. *Export to file*, pick a location, and remember the file. Per-table row counts are logged.

Move the file to the target device (USB, SD card, cloud, whatever).

On the **target** device:

1. Make sure the destination account already has a calendar. Google Calendar creates a default
   one when the account is added.
2. *Choose backup file to import*.
3. Pick the destination calendar, tick the source calendars you want, leave *Skip events already
   in the destination calendar* on, and press *Start import*.
4. *Verify a backup against the destination* to compare what is now stored against the backup.
   *Save last report* writes it to a file.

Running the import twice with the skip option on creates nothing the second time: each event is
matched by its `UID_2445`, which the importer sets from the backup.

## What is preserved

Everything a user sees inside an event: title, notes, location, start and end, all-day flag,
time zones, the recurrence rule and its dates, recurrence overrides (re-created through the
provider's own exception path so they stay attached to their series), cancellations, privacy
level, availability, organizer, guest permissions, the event colour, the iCalendar UID, and all
attendees with their replies, plus all reminders.

## What is deliberately *not* copied

Fields that describe the source device, its account or the containing app are excluded on
purpose: copying them would be wrong on the destination even though it would look like "less
loss". Sync bookkeeping belongs to the source account's adapter; row identity is reassigned by
the destination provider; `selfAttendeeStatus` is recomputed from the attendee rows, and only
from the attendee whose address matches the destination calendar's owner account;
`customAppPackage`/`customAppUri` point at the app that owns the event's UI, with an opaque URI
that only that app can read; `attendeeIdentity` and `attendeeIdNamespace` are the source account's
identity namespace; `*_color_index` values are keys that may not resolve on the destination; and
the `lastDate` / `displayColor` / `has*` / `isOrganizer` / `canInviteOthers` family is computed by
the provider from the data that *is* copied.

Each field has its own verdict and reason in the table below.

## Every field, in full

[field-table.md](field-table.md) lists every column of every table the app reads, what it means,
whether it is reproduced on the destination, and whether iCalendar could have carried it instead.

## What is lost, and why

- **Calendar grouping** is whatever you choose in the picker. Two source calendars mapped to one
  destination merge.
- **Calendar metadata** (name, colour, visibility, reminder limits) is not restored: the
  destination calendar is an existing one and keeps its own.
- **`Colors` and `ExtendedProperties`** are not restored; the provider only accepts those from a
  sync adapter.
- **After the first sync**, the account's own adapter may rewrite `uid2445`, `eventColor` and the
  `guestsCan*` flags. Run *Verify* before the first sync for the cleanest comparison.
- **Anything the source calendar app kept outside the provider** — its own database, notes,
  attachments, app-specific reminder semantics — is invisible to any provider-based tool.

If the export reports zero rows, the source app is not using the system provider at all and this
approach cannot reach its data.

## Verification

*Verify* re-reads the destination calendar and diffs it field by field against the backup. It
compares exactly the columns the write path can preserve — including the null case of the colour
fields, since a null colour means "inherit the calendar's colour" and must not become black — and
compares attendees and reminders as sets. It then prints a fixed *not compared, and why* list, so
that a known limitation never looks like a bug and a real one is never hidden.

## Building and testing

See the [repository README](../README.md): `./gradlew test assembleDebug` builds and tests the
app, and `scripts/build.sh` wraps that plus copying the APK into `outputs/`.
