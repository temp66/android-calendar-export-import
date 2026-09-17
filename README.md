# Android calendar export and import

A sideloaded Android app that exports a calendar from one device to a single JSON file and
imports it into another, by reading the system calendar provider directly and replaying it
through the provider's own write path. No root, no export function in the source app, no ICS, no
cloud service.

## The problem

Moving a calendar off a phone normally means an export, a cloud account, or a USB debugging
session. If the app has no export, the device has no Google Play services and the bootloader is
locked, the only route left is the system calendar provider itself — and the usual trick, asking
a third-party app to serialise everything to ICS, silently drops a lot: calendar grouping,
colours, guest permissions, the link to an app that owns an event's richer UI, email and SMS
reminders, and often the link between a recurring series and its edited instances.

This app takes the other route. It dumps every column of every provider table to a JSON file,
then inserts events, recurrence overrides, attendees and reminders into a calendar that already
exists on the target. The provider marks each inserted event dirty, so the destination account's
own sync adapter uploads it — the events end up looking as if they had been created there.

Measured against ICS, the specific losses avoided are in
[docs/ics-loss-analysis.md](docs/ics-loss-analysis.md), and a per-field verdict for all 125
columns the app touches is in [docs/field-table.md](docs/field-table.md).

## Status

- Behaviour is implemented against the provider's real write path, read from AOSP; see
  [third_party/aosp](third_party/aosp) for the sources the rules were taken from.
- 19 JVM tests drive the whole export → import → verify cycle against an in-memory fake provider
  that enforces those same rules.
- The APK has **not** been run against a real device yet: the environment it was built in has no
  emulator and no phone. The in-app *Verify* screen is the acceptance test.
- Scope: the destination account and calendar must already exist (case 1 of the four cases in the
  [manual](docs/manual.md#why-only-existing-account-existing-calendar)). Creating calendars or
  accounts is deliberately not implemented.

## Requirements

- JDK 17
- Android SDK with platform 36 and build-tools 36.0.0. Android Studio installs both; on a headless
  machine `sdkmanager "platforms;android-36" "build-tools;36.0.0"` does the same.
- Nothing else. The app has no third-party runtime dependencies — JSON comes from the platform's
  `org.json` — and the build resolves only the Android Gradle Plugin, Kotlin (built into AGP 9)
  and JUnit.

## Build

```bash
./gradlew test assembleDebug     # tests, then the APK in app/build/outputs/apk/debug/
./gradlew lint                   # Android lint
scripts/build.sh                 # the same, then copies the APK to outputs/
```

On a machine with neither a JDK nor the SDK — a container or a bare CI runner:

```bash
scripts/bootstrap-toolchain.sh   # provisions tools/ (about 2 GB)
```

The Gradle wrapper pins Gradle 9.6.0 and `gradle/libs.versions.toml` pins every plugin and
library version, so the build does not drift.

## Install

```bash
adb install -r outputs/android-calendar-export-import-debug.apk
```

Then follow the [manual](docs/manual.md) for the two-device procedure.

## Repository layout

| Path | What it is |
|---|---|
| `app/` | The Android application module: Kotlin sources, resources, JVM tests |
| `docs/` | The [manual](docs/manual.md), the generated [field table](docs/field-table.md), and the [ICS loss analysis](docs/ics-loss-analysis.md) |
| `gradle/` | Version catalog and the committed Gradle wrapper |
| `scripts/` | Toolchain bootstrap, build, trust store, and the field table generator |
| `third_party/aosp/` | Unmodified AOSP sources the provider rules were read from |
| `outputs/` | Where the built APK lands (gitignored; release assets belong here at publish time) |
| `work/` | Scratch: downloaded toolchain archives |

## Tests

`./gradlew test` runs the unit tests on the JVM. They exercise the export and import logic
against an in-memory fake provider that mirrors `CalendarProvider2`: sync-only columns are
refused without the sync-adapter parameter, an event needs a calendar id, a time zone, a start
and exactly one of end or duration, an unparseable `RRULE` is rejected, a recurrence exception
may only set the provider's whitelist and inherits the rest from its series, and a batch that
fails as a whole is retried row by row so one bad row cannot lose a hundred good ones. They also
assert that identity- and sync-owned columns are never written, that importing the same backup
twice creates nothing, and that a 5,000-event calendar imports in seconds.

## Licence

Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Not affiliated with Google. Android is a
trademark of Google LLC.
