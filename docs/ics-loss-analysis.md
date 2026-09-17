# Android calendar export and import: what an ICS round trip preserves and what it loses

> This is the analysis that motivated this repository's tool. For the app that avoids these
> losses by replaying the provider's own data instead of serialising to ICS, see the
> [manual](manual.md).

**Scenario.** Source device: locked bootloader, no root, no GMS. The calendar app has no
export function. Target device: unlocked bootloader, root, GMS. Proposed bridge: a third-party
app reads the system calendar provider (`CalendarContract`) and serializes it to ICS, which is
then imported on the target.

**Sources used.**

- `CalendarContract` schema, read from AOSP `frameworks/base/core/java/android/provider/CalendarContract.java`,
  vendored under `third_party/aosp/`
  (the definitions behind the developer.android.com calendar-provider page).
- [RFC 5545](https://www.rfc-editor.org/rfc/rfc5545) (iCalendar) for the properties ICS can
  carry.
- [RFC 7986](https://www.rfc-editor.org/rfc/rfc7986) for newer iCalendar properties (COLOR,
  IMAGE, NAME, ...).

**Bottom line.** The event core survives: title, notes, location, start/end, all-day flag, time
zone, recurrence rule, attendees and their replies, and display reminders. Almost everything
outside that core is lost: per-calendar metadata, colours, guest-permission flags, app deep
links, sync-adapter scratch fields, extended properties, email/SMS reminders, and most
consequentially the grouping of events into separate calendars and the linkage between
recurring events and their overridden instances.

---

## 1. What the provider actually stores

Authority `com.android.calendar`. Column counts below are exact for the AOSP schema and exclude
the autoincrement `_ID` that every table also has; the child tables look large because they
inherit the whole event projection.

| Table | Columns | Contents |
|---|---|---|
| `Calendars` | 34 | one row per calendar: account, display name, colour, access level, visibility, time zone, capability flags, `cal_sync1..10` |
| `Events` | 81 | one row per event or recurring series |
| `Instances` | 71 | computed expansion of recurrences (not authoritative) |
| `Attendees` | 52 | 3 own columns (`attendeeName`, `attendeeEmail`, `event_id`) plus the full event projection |
| `Reminders` | 52 | 3 own columns (`minutes`, `method`, `event_id`) plus the full event projection |
| `ExtendedProperties` | 52 | 3 own columns (`name`, `value`, `event_id`) — sync-adapter scratch space |
| `CalendarAlerts` | 59 | runtime notification state (`alarmTime`, `notifyTime`, `receivedTime`, `state`, ...) |
| `Colors` | 3 | per-account colour palette (`color_type`, `color_index`, `color`) |
| `CalendarCache` | 2 | key/value: `timezoneType`, `timezoneInstances`, `timezoneInstancesPrevious`, `localTimezone` |
| `CalendarMetaData` | 5 | `localTimezone`, min/max instance, min/max event days |
| `EventDays` | 2 | index of days that contain events |
| `EventsRawTimes` | 5 | raw RFC2445 strings from the sync source (`dtstart2445`, `dtend2445`, ...) |
| `SyncState` | — | sync bookkeeping |

Column groups by interface:

- `EventsColumns` (49) — the actual event payload.
- `CalendarColumns` (15) — `calendar_color`, `calendar_color_index`, `calendar_displayName`,
  `calendar_access_level`, `visible`, `calendar_timezone`, `sync_events`, `ownerAccount`,
  `canOrganizerRespond`, `canModifyTimeZone`, `maxReminders`, `allowedReminders`,
  `allowedAvailability`, `allowedAttendeeTypes`, `isPrimary`.
- `SyncColumns` (7) plus `CalendarSyncColumns` (10) — `account_name`, `account_type`, `_sync_id`,
  `dirty`, `mutators`, `deleted`, `canPartiallyUpdate`, `cal_sync1..10`.

Storage conventions that matter for a faithful export:

- `dtstart` and `dtend` are UTC milliseconds; the wall-clock zone lives separately in
  `eventTimezone` and `eventEndTimezone`.
- `duration` is an RFC 2445 duration string, used instead of `dtend` for some recurring events.
- `rrule`, `rdate`, `exdate`, `exrule` are RFC 2445 rule strings.
- All-day events set `allDay=1` and anchor `dtstart` at UTC midnight.
- An overridden instance of a series is a separate row in `Events`, linked back through
  `original_id`, `original_sync_id`, `originalInstanceTime` and `originalAllDay`.

## 2. What ICS can carry

`VEVENT` (RFC 5545): `DTSTAMP`, `UID`, `DTSTART`, `CLASS`, `CREATED`, `DESCRIPTION`, `GEO`,
`LAST-MODIFIED`, `LOCATION`, `ORGANIZER`, `PRIORITY`, `SEQUENCE`, `STATUS`, `SUMMARY`, `TRANSP`,
`URL`, `RECURRENCE-ID`, `RRULE`, `DTEND`, `DURATION`, `ATTACH`, `ATTENDEE`, `CATEGORIES`,
`COMMENT`, `CONTACT`, `EXDATE`, `REQUEST-STATUS`, `RELATED-TO`, `RESOURCES`, `RDATE`, plus X- and
IANA-registered properties.

`VALARM`: `ACTION`, `TRIGGER`, `REPEAT`, `DURATION`, `ATTACH`, `DESCRIPTION`, `SUMMARY`,
`ATTENDEE`.

`VCALENDAR`: `PRODID`, `VERSION`, `CALSCALE`, `METHOD`.

RFC 7986 later added `NAME`, `DESCRIPTION`, `UID`, `LAST-MODIFIED`, `URL`, `CATEGORIES`,
`REFRESH-INTERVAL`, `SOURCE`, `COLOR`, `IMAGE` and `CONFERENCE`. These exist on paper but are
rarely emitted by exporters and rarely honoured by importers, Android's included.

Notable absences: `EXRULE` existed in RFC 2445 but was removed in RFC 5545; there is no
per-attendee permission flag, no per-event colour in the Android sense, and no way to express
several distinct calendars inside one iCalendar object.

---

## 3. Column-by-column mapping

### 3.1 `Events` (81 columns)

| Provider column | ICS carrier | Result |
|---|---|---|
| `title` | `SUMMARY` | preserved |
| `description` | `DESCRIPTION` | preserved |
| `eventLocation` | `LOCATION` | preserved |
| `dtstart` + `eventTimezone` | `DTSTART;TZID=...` | preserved only if the exporter combines the UTC value with `eventTimezone`. Exporters that emit `DTSTART:...Z` silently shift recurring events across DST boundaries. |
| `dtend` + `eventEndTimezone` | `DTEND` | same caveat |
| `duration` | `DURATION` | preserved |
| `allDay` | `DTSTART;VALUE=DATE` | preserved; exporters that keep the UTC midnight instant can shift the day |
| `rrule` | `RRULE` | preserved (same syntax family) |
| `rdate` | `RDATE` | preserved |
| `exdate` | `EXDATE` | preserved |
| `organizer` | `ORGANIZER` | preserved |
| `uid2445` | `UID` | preserved only if the exporter uses this column; otherwise a fresh UID is minted and external references to the old identity break |
| `eventStatus` (tentative/confirmed/cancelled) | `STATUS` | preserved |
| `accessLevel` (default/confidential/private/public) | `CLASS` | partial: four provider values collapse into three ICS values |
| `availability` (busy/free/tentative) | `TRANSP` (OPAQUE/TRANSPARENT) | partial: ICS is boolean, so tentative has no exact form |
| `selfAttendeeStatus` | `PARTSTAT` on your own `ATTENDEE` | partial: only if your own attendee row survives |
| `originalInstanceTime` | `RECURRENCE-ID` | partial: required to represent an overridden instance, frequently dropped |
| `exrule` | — | lost (removed from RFC 5545) |
| `original_id`, `original_sync_id`, `originalAllDay` | — | lost; see section 4.2 |
| `eventColor`, `eventColor_index`, `displayColor` | RFC 7986 `COLOR` | lost in practice: Android stores ARGB integers, exporters do not emit COLOR, importers ignore it |
| `guestsCanModify`, `guestsCanInviteOthers`, `guestsCanSeeGuests` | — | lost (iCalendar has no guest-permission concept) |
| `customAppPackage`, `customAppUri` | — | lost (deep link into the source app's own event screen) |
| `sync_data1` ... `sync_data10` | — | lost (private to the source sync adapter) |
| `hasAlarm`, `hasExtendedProperties`, `hasAttendeeData`, `lastDate`, `isOrganizer`, `canInviteOthers`, `lastSynced` | — | derived or regenerated, not real loss |
| `calendar_id` | — | lost as identity: becomes a new calendar on import, and typically all sources collapse into one |
| `account_name`, `account_type`, `_sync_id`, `dirty`, `mutators`, `deleted`, `canPartiallyUpdate`, `cal_sync1..10` | — | lost (sync bookkeeping, regenerated for the new account) |
| `calendar_color`, `calendar_color_index`, `calendar_displayName`, `calendar_access_level`, `visible`, `calendar_timezone`, `sync_events`, `ownerAccount`, `canOrganizerRespond`, `canModifyTimeZone`, `maxReminders`, `allowedReminders`, `allowedAvailability`, `allowedAttendeeTypes`, `isPrimary` | — | lost; these are calendar-level and have no ICS carrier beyond the non-standard `X-WR-CALNAME` / `X-WR-TIMEZONE` |

### 3.2 `Calendars` (34 columns)

ICS has no first-class calendar object, so this table is almost entirely lost:

| Provider column | Result |
|---|---|
| `calendar_displayName` | only via the de-facto `X-WR-CALNAME` (or RFC 7986 `NAME`); often ignored, in which case the calendar is merged or renamed |
| `calendar_timezone` | partial, via `X-WR-TIMEZONE` or a `VTIMEZONE` block |
| `calendar_color`, `calendar_color_index`, `calendar_access_level`, `visible`, `sync_events`, `calendar_location`, `ownerAccount`, `name`, `isPrimary` | lost |
| `canOrganizerRespond`, `canModifyTimeZone`, `maxReminders`, `allowedReminders`, `allowedAvailability`, `allowedAttendeeTypes` | lost (capability flags; the target re-derives defaults) |
| `account_name`, `account_type`, `_sync_id`, `dirty`, `mutators`, `deleted`, `canPartiallyUpdate`, `cal_sync1..10` | lost |

### 3.3 `Attendees` (3 own columns)

| Provider column | ICS carrier | Result |
|---|---|---|
| `attendeeName` | `ATTENDEE;CN=` | preserved |
| `attendeeEmail` | `ATTENDEE` value | preserved |
| `attendeeStatus` (none/accepted/declined/invited/tentative) | `PARTSTAT` (NEEDS-ACTION/ACCEPTED/DECLINED/TENTATIVE/DELEGATED) | preserved, with NONE becoming NEEDS-ACTION |
| `attendeeType` (none/required/optional/resource) | `CUTYPE` (INDIVIDUAL/GROUP/RESOURCE/ROOM/UNKNOWN) | partial: no ROOM distinction is stored and NONE has no clean mapping |
| `attendeeRelationship` (none/attendee/organizer/performer/speaker) | `ROLE` (REQ-PARTICIPANT/OPT-PARTICIPANT/NON-PARTICIPANT/CHAIR) | partial: performer and speaker have no ICS equivalent |
| `attendeeIdentity`, `attendeeIdNamespace` | — | lost (iTIP reply identity and namespace; non-standard) |

### 3.4 `Reminders` (3 own columns)

| Provider column | ICS carrier | Result |
|---|---|---|
| `minutes` | `TRIGGER` | preserved, except `minutes = -1` (`MINUTES_DEFAULT`), meaning "use the system default", which has no ICS representation |
| `method` = DEFAULT / ALERT / ALARM | `VALARM;ACTION=DISPLAY` | preserved |
| `method` = EMAIL | `VALARM;ACTION=EMAIL` | technically expressible but needs `ATTENDEE`, `SUMMARY`, `DESCRIPTION` and `TRIGGER`; most exporters do not emit it, so usually lost |
| `method` = SMS | — | lost (no SMS action in iCalendar) |
| several reminders on one event | several `VALARM` blocks | partially preserved; most exporters write a single alarm and most importers keep only one |

### 3.5 `ExtendedProperties` (3 own columns)

| Provider column | Result |
|---|---|
| `name`, `value` | lost unless the exporter deliberately writes them as X- properties. This is where sync adapters stash protocol-specific data, so all of it goes. |

### 3.6 Runtime and derived tables

| Table | Result |
|---|---|
| `CalendarAlerts` (`alarmTime`, `notifyTime`, `receivedTime`, `creationTime`, `state`, `minutes`) | lost, but this is only notification delivery state and is regenerated on the target |
| `CalendarCache` (`timezoneType`, `timezoneInstances`, ...) | lost; it is a cache |
| `CalendarMetaData` (`minInstance`, `maxInstance`, `minEventDays`, `maxEventDays`) | regenerated |
| `EventDays` | regenerated |
| `EventsRawTimes` (`dtstart2445`, ...) | lost; raw sync-source strings, derivable when a TZID was preserved |
| `SyncState` | lost |
| `Colors` (`color_type`, `color_index`, `color`) | lost, and the index columns in `Calendars` and `Events` lose their referents |

---

## 4. Structural losses

These matter more in practice than any single column.

### 4.1 Multiple calendars collapse

iCalendar's top level is a single `VCALENDAR`; a calendar is not an addressable object with an
ID. If the source device holds several calendars (a local one, an app-private one, a holidays
calendar, a birthday calendar generated from contacts), a single `.ics` merges them into one,
and their names, colours, visibility and access levels are gone. A careful exporter writes one
`.ics` per `Calendars` row; many do not.

### 4.2 Recurrence overrides and per-instance deletions

An edited instance of a recurring event is a separate `Events` row linked by `original_id`,
`originalInstanceTime` and `originalAllDay`. To represent it in ICS you must emit it as a
`VEVENT` with the same `UID` plus a `RECURRENCE-ID`. Two failure modes are common:

- The exporter mints a new UID: the override imports as an unrelated extra event while the
  original unchanged instance is still generated, so the entry appears twice.
- The exporter drops the exception row: the edit is lost and the original instance reappears.

Single-instance deletions are usually stored as cancelled exception rows
(`eventStatus = STATUS_CANCELED`), so they tend to come back as normal events.

### 4.3 Time zone fidelity

`dtstart` is UTC millis and `eventTimezone` is a separate string. If the exporter emits UTC
instead of `DTSTART;TZID=Europe/Berlin:...`, the series looks correct until the next DST
transition, after which every occurrence drifts by an hour. All-day events anchored at UTC
midnight can shift by a day when the target device sits in a negative UTC offset.

### 4.4 Value-set narrowing

| Provider concept | ICS concept | Effect |
|---|---|---|
| `availability`: busy / free / tentative | `TRANSP`: OPAQUE / TRANSPARENT | tentative has no exact form |
| `accessLevel`: default / confidential / private / public | `CLASS`: PUBLIC / PRIVATE / CONFIDENTIAL | default has no distinct form |
| `attendeeType`: required / optional / resource / none | `CUTYPE`: INDIVIDUAL / GROUP / RESOURCE / ROOM / UNKNOWN | room versus resource cannot be recovered |
| `attendeeRelationship`: performer / speaker | `ROLE`: CHAIR / REQ-PARTICIPANT / OPT-PARTICIPANT / NON-PARTICIPANT | performer and speaker are dropped |
| `method`: email / sms | `ACTION`: DISPLAY / EMAIL / AUDIO | sms is dropped |
| `EXRULE` | — | dropped |

### 4.5 Data that never reaches the provider

Anything the app keeps in its own private database — task lists, attachments, note formatting,
app-specific reminder semantics, custom fields not written to `ExtendedProperties` — is
invisible to a provider-based exporter. No ICS export can recover it, and neither can a
provider dump (see section 6).

---

## 5. Practical caveats for this specific pair of devices

1. Verify the app uses the provider first. Many calendar apps keep their own store. Install a
   second calendar app on the source device and check whether the events appear, or query
   `Calendars` and look for an `account_name` matching the app. If the app has its own store,
   the provider holds nothing and the whole approach is void.
2. No GMS means no Play Store, so the exporter must be sideloaded (via `adb install` with USB
   debugging enabled, or an F-Droid build). `READ_CALENDAR` is a runtime permission that has to
   be granted interactively.
3. `adb shell content query --uri content://com.android.calendar/events` is worth trying first,
   because it needs no app installed at all. The Shell app that backs `adb shell` is a
   privileged, platform-signed app that declares both `READ_CALENDAR` and `WRITE_CALENDAR`, so
   the query succeeds on many builds; on others it returns a `SecurityException`, so treat it as
   a quick experiment rather than the plan. `adb backup` does not cover the calendar provider.
4. Import is the second lossy step. Importing through Google Calendar's web UI creates new
   events in a calendar of your choice and is known to drop colours and guest permissions,
   sometimes attendee lists, and it has a small per-file size limit, so large exports must be
   split. An alternative is ICSx5, which subscribes to the `.ics` and syncs it into a local
   calendar: it keeps more, but puts everything into one calendar and keeps re-syncing.
5. Special calendars become real events. Birthday calendars generated from contacts and holiday
   calendars are materialized into ordinary events on import, duplicating data the target
   device would generate itself.

## 6. A higher-fidelity alternative

The source device is the bottleneck, not the target, so root on the target does not help read
the source's app-private data. But if the data is in the provider, a generic ICS export is a
lossy way to move it:

**Root is not required for any of this.** The provider declares a single
`android:readPermission="android.permission.READ_CALENDAR"` and
`android:writePermission="android.permission.WRITE_CALENDAR"` for the whole authority, with no
per-path restrictions. Internally it states that "queries are never restricted to app- or
sync-adapter-only, and we don't restrict the set of columns that may be accessed", so an
ordinary sideloaded app holding `READ_CALENDAR` can read every column of every table, including
`Colors`, `ExtendedProperties` and `CalendarAlerts`. Two further details are worth knowing:

- Rows carrying `lastSynced = 1` (the pre-edit copies kept for partially updatable calendars)
  are hidden from ordinary queries, but the filter is applied only when the caller is not
  declared as a sync adapter, and "sync adapter" is decided purely by the
  `caller_is_syncadapter=true` URI query parameter, which any caller may add. The same applies
  to writing sync-only columns on restore.
- Root is only required for data that is *not* in the provider (the app's private database
  under `/data/data/<package>/`), or if you want to bypass the provider entirely and read its
  SQLite file with `sqlite3`.

- Ship a small exporter (sideloaded, same `READ_CALENDAR` permission) that dumps every public
  table and every column to JSON, or to a reconstructable SQLite file: `Events` in full
  (including `sync_data*`, `eventColor`, guest flags and `original_id` chains), `Calendars`,
  `Attendees`, `Reminders`, `ExtendedProperties`, `Colors`, `CalendarAlerts`.
- Restore on the rooted target by writing directly into the provider, either through
  `CalendarContract` with the `caller_is_syncadapter=true` URI parameter, which allows setting
  `account_name`, `account_type` and `_sync_id` and thus preserves identity, or via `sqlite3` on
  the provider database with the calendar app stopped.

That preserves essentially everything the provider holds. What it still cannot recover is
anything the source app kept outside the provider.

## 7. Verification checklist after the import

- Event count per source calendar versus per target calendar (watch for the merge in 4.1).
- Count events with `original_id` set, and confirm each maps to exactly one target event rather
  than two.
- Recurring events crossing a DST boundary: compare the wall-clock time before and after the
  transition on both devices.
- All-day events, especially those created on a device in a different UTC offset.
- Events with attendees: compare the attendee count and each `PARTSTAT`.
- Events carrying more than one reminder, and any with an email or SMS reminder.
- Any event that had a per-event colour or a custom app link.

---

## Appendix: existing apps, as of September 2026

### A. ICS import/export apps (the classic solution; all non-root, all read the provider)

| App | Package | Where | Notes |
|---|---|---|---|
| Calendar Import-Export | `org.sufficientlysecure.ical` | F-Droid (2.8.1), formerly Play | GPLv3, source at github.com/SufficientlySecure/calendar-import-export. "Import, Export & Backup your calendars using ics files". The reference implementation of the approach in this document. |
| Calendar Backup | `kfsoft.calendar.backup.ics` | Play, 50k+ installs, v1.0.20 (Oct 2025) | "Backup / Restore and Manage your Calendars (Import & export ICS / iCal File) ... backup / change color / rename / clear / delete your calendar storage" |
| Cal2CSV | `com.calendarexporter.calendar_exporter` | Play | Exports events to CSV or ICS, with a selectable date range and multiple calendars |
| iCal Import/Export Premium | `tk.drlue.icalimportexport.premium` | Play | Successor to the long-standing iCal Import/Export |
| CalendarSync (trial) | `com.icalparse.free` | Play | Older export/sync utility |

All of these hold `READ_CALENDAR` and optionally `WRITE_CALENDAR` and no root. They are exactly
the "common solution" described at the top, and they inherit every loss in section 3.

### B. Non-root apps that read and write the full provider data model

These are the evidence that the higher-fidelity route needs no root — ordinary, unprivileged
apps performing the same operations this document proposes:

| App | Package | What it proves |
|---|---|---|
| ICSx5 | `at.bitfire.icsdroid` | `LocalCalendar.kt` calls `asSyncAdapter(account)` and queries `Events.UID_2445` and `Events.ORIGINAL_SYNC_ID`, i.e. sync-exposed columns, from a plain app |
| ical4android (library) | github.com/bitfireAT/ical4android | `MiscUtils.kt` implements `asSyncAdapter` as appending `ACCOUNT_NAME`, `ACCOUNT_TYPE` and `CALLER_IS_SYNCADAPTER=true` — the exact parameter the provider accepts without verifying anything |
| DAVx5 | `at.bitfire.davdroid` | `LocalCalendarStore.kt` writes `ACCOUNT_NAME`, `ACCOUNT_TYPE`, `OWNER_ACCOUNT`, `VISIBLE`, `SYNC_EVENTS`, `CALENDAR_DISPLAY_NAME`, `ALLOWED_REMINDERS`, `IS_PRIMARY`, `CALENDAR_COLOR`, `CALENDAR_ACCESS_LEVEL` and `CAN_ORGANIZER_RESPOND` when creating a calendar |
| Calendar Color | `ch.ihdg.calendarcolor` | A ~100-line app whose entire purpose is writing `Calendars.CALENDAR_COLOR`, a calendar-level field ICS cannot carry, with only `READ_CALENDAR`/`WRITE_CALENDAR` |
| CalSync | `dev.henriquecouto.calsync` | Reads and writes events across local calendars entirely on-device |
| EteSync | `com.etesync.syncadapter` | Non-root, end-to-end encrypted calendar/task sync into the provider |
| jtx Board | `at.techbee.jtx` | Non-root VTODO/VJOURNAL sync into the provider |

### C. What does not exist

No off-the-shelf app performs the raw "dump every table and column to JSON or SQLite" approach
described in section 6. That was checked against the complete F-Droid catalogue (4,394 apps),
Play Store listings and GitHub, and the result is consistent: the tools that do a *raw* calendar
backup are root-based (Swift Backup, Titanium Backup, Neo Backup/oandbackupX) or are
device-vendor migration tools such as Samsung Smart Switch.

So "ship a small exporter" means exactly that: it has to be written. The permissions and APIs
are available without root, as the apps above demonstrate, but the specific tool is not on a
shelf.
