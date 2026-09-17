#!/usr/bin/env python3
"""Regenerates docs/field-table.md.

Two sources, so the table cannot drift from either:

  * the provider schema in third_party/aosp/CalendarContract.java, for the column names and
    their descriptions;
  * the policy in app/src/main/java/dev/caltransfer/providerdump/ColumnPolicy.kt, parsed here
    rather than copied, for the verdicts.

Run it after changing either:

    scripts/field-table.py
"""

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
AOSP = ROOT / "third_party/aosp/CalendarContract.java"
POLICY = ROOT / "app/src/main/java/dev/caltransfer/providerdump/ColumnPolicy.kt"
DOC = ROOT / "docs/field-table.md"
BEGIN = "<!-- BEGIN FIELD TABLE -->"
END = "<!-- END FIELD TABLE -->"

# ---------------------------------------------------------------- schema

INTERFACES = [
    "EventsColumns", "SyncColumns", "CalendarSyncColumns", "CalendarColumns",
    "AttendeesColumns", "RemindersColumns", "ExtendedPropertiesColumns", "ColorsColumns",
]


def interface_body(source, name):
    match = re.search(r"interface\s+" + name + r"\b[^{]*\{", source)
    index = match.end() - 1
    depth = 0
    for position in range(index, len(source)):
        if source[position] == "{":
            depth += 1
        elif source[position] == "}":
            depth -= 1
            if depth == 0:
                return source[index:position]
    raise SystemExit("could not find interface " + name)


def extract_schema():
    source = AOSP.read_text(encoding="utf-8")
    fields = {}
    pattern = re.compile(
        r"(/\*\*(.*?)\*/\s*)?public static final String\s+[A-Z0-9_]+\s*=\s*\"([^\"]+)\"\s*;",
        re.S,
    )
    for interface in INTERFACES:
        body = interface_body(source, interface)
        fields[interface] = [
            (m.group(3), cleanup_doc(m.group(2) or ""))
            for m in pattern.finditer(body)
        ]
    return fields


def cleanup_doc(doc):
    """Javadoc lines begin with an asterisk; drop it before collapsing the text to one line."""
    doc = re.sub(r"(?m)^\s*\*\s?", "", doc)
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", doc)).strip()


# ---------------------------------------------------------------- policy

def parse_policy():
    """Reads the column constants and the column sets straight out of ColumnPolicy.kt."""
    text = POLICY.read_text(encoding="utf-8")

    constants = {}
    for block in re.finditer(r"^object (\w+) \{(.*?)^\}", text, re.S | re.M):
        owner, body = block.group(1), block.group(2)
        for const in re.finditer(r'const val (\w+) = "([^"]+)"', body):
            constants[f"{owner}.{const.group(1)}"] = const.group(2)

    sets = {}
    for block in re.finditer(r"^\s*val ([A-Z_]+)(?::[^=\n]+)? = (?:setOf|listOf)\(", text, re.M):
        # Match the closing parenthesis by depth rather than by pattern: some of these sets are
        # written on one line and some span many, and a lazy pattern runs past the end of an
        # inline set into the next one.
        depth, index = 1, block.end()
        while index < len(text) and depth > 0:
            depth += {"(": 1, ")": -1}.get(text[index], 0)
            index += 1
        if depth != 0:
            raise SystemExit(f"unbalanced parentheses while reading {block.group(1)}")
        name, body = block.group(1), text[block.end():index - 1]
        values = re.findall(r'"([^"]*)"', body)
        for owner, const in re.findall(r"\b([A-Z]\w*)\.([A-Z0-9_]+)\b", body):
            key = f"{owner}.{const}"
            if key in constants:
                values.append(constants[key])
        sets[name] = values

    missing = [
        name for name in
        ("ALWAYS_DROP", "PROVIDER_OWNED_EVENT_COLUMNS", "DERIVED_EVENT_COLUMNS",
         "SYNC_ONLY_EVENT_COLUMNS", "COLOR_KEY_COLUMNS", "NOT_COPIED_AS_IS",
         "VERIFY_EVENT_FIELDS", "VERIFY_ATTENDEE_FIELDS", "VERIFY_REMINDER_FIELDS")
        if not sets.get(name)
    ]
    if missing:
        raise SystemExit("could not parse these policy sets: " + ", ".join(missing))
    return sets


POLICY_SETS = parse_policy()
ALWAYS_DROP = set(POLICY_SETS["ALWAYS_DROP"])
PROVIDER_OWNED = set(POLICY_SETS["PROVIDER_OWNED_EVENT_COLUMNS"])
DERIVED = set(POLICY_SETS["DERIVED_EVENT_COLUMNS"])
SYNC_ONLY = set(POLICY_SETS["SYNC_ONLY_EVENT_COLUMNS"])
COLOR_KEY = set(POLICY_SETS["COLOR_KEY_COLUMNS"])
NOT_AS_IS = set(POLICY_SETS["NOT_COPIED_AS_IS"])
VERIFY_EVENT_FIELDS = set(POLICY_SETS["VERIFY_EVENT_FIELDS"])
VERIFY_ATTENDEE_FIELDS = set(POLICY_SETS["VERIFY_ATTENDEE_FIELDS"])
VERIFY_REMINDER_FIELDS = set(POLICY_SETS["VERIFY_REMINDER_FIELDS"])

R_SYNC = ("sync bookkeeping owned by the source account's adapter: meaningless in another "
          "account, and the destination provider rewrites it anyway")
R_ACL = ("a pointer to the app that owns this event's UI, not event content: the calendar app "
         "hands such an event to that package through ACTION_HANDLE_CUSTOM_EVENT, and the paired "
         "URI is that app's own identifier for it. Neither can travel: the package need not exist "
         "on the destination, and the URI names a record in the source device's copy of that app")
R_IDENT = "the source account's identity / sync namespace, which does not exist for the destination user"
R_DERIVED = "recomputed by the destination provider from the data that is copied"
R_COLORKEY = ("a colour **key**; an unresolvable key either fails the insert outright or silently "
              "nulls the colour, so only the literal colour is written")
R_OWNED = "a calendar attribute surfaced on the event row; the destination calendar already exists and keeps its own"
R_RSVP = ("the provider recomputes it from the attendee rows, but only from the attendee whose "
          "address equals the destination calendar's owner account, so it can end up 'none' when "
          "the source and target accounts differ. The reply itself is carried by that attendee row")
R_LOCAL = ("local row identity; the destination provider assigns its own and the backup id is "
           "used only to remap relationships")
R_CAL = "the destination calendar already exists, so its name, colour, visibility and limits are left untouched"
R_ACCT = "identifies the source account; the destination calendar belongs to a different account"
R_EXT = "the ExtendedProperties table is writable only by a sync adapter, which this app is not"
R_COLORS = "the Colors table is writable only by a sync adapter, which this app is not"

REMAPPED = {
    "_id": R_LOCAL,
    "calendar_id": "the destination calendar id is written instead",
    "event_id": "rewritten to the new event id the destination provider assigned",
    "original_id": "set to the new row id of the series when the provider creates the override",
}

# Not written, but recomputed by the destination provider from data that is copied.
DERIVED_REASONS = {
    "selfAttendeeStatus": R_RSVP,
    "hasAlarm": "recomputed from the reminders that are copied",
    "hasAttendeeData": "recomputed from the attendee rows that are copied",
    "hasExtendedProperties": "recomputed from ExtendedProperties, which is not copied, so it reads 0 on the destination",
    "lastDate": "recomputed from the recurrence rule",
    "isOrganizer": "recomputed by comparing organizer with the destination calendar's owner account",
    "canInviteOthers": "computed by the provider from the guest permissions and the access level",
    "displayColor": "computed as the event colour, falling back to the destination calendar's colour",
    "originalAllDay": "inherited from the series when the provider creates the override",
}

# iCalendar support, per column: yes = an RFC 5545 property carries it, partial = carried with a
# caveat, no = iCalendar has no equivalent. This is documentation, not app behaviour, so it lives
# here rather than being derived from the code.
ICS_YES = {
    "title": "`SUMMARY`", "description": "`DESCRIPTION`", "eventLocation": "`LOCATION`",
    "dtstart": "`DTSTART` (with `TZID`)", "dtend": "`DTEND`", "duration": "`DURATION`",
    "eventTimezone": "the `TZID` parameter on `DTSTART`",
    "eventEndTimezone": "the `TZID` parameter on `DTEND`",
    "allDay": "`DTSTART;VALUE=DATE`", "rrule": "`RRULE`", "rdate": "`RDATE`", "exdate": "`EXDATE`",
    "eventStatus": "`STATUS`", "organizer": "`ORGANIZER`", "uid2445": "`UID`",
    "originalInstanceTime": "`RECURRENCE-ID`", "attendeeName": "`ATTENDEE;CN=`",
    "attendeeEmail": "the `ATTENDEE` value", "attendeeStatus": "`PARTSTAT`",
    "selfAttendeeStatus": "`PARTSTAT` on your own `ATTENDEE`",
}
ICS_PARTIAL = {
    "accessLevel": "`CLASS` has PUBLIC / PRIVATE / CONFIDENTIAL, so the provider's \"default\" has no form",
    "availability": "`TRANSP` is only OPAQUE / TRANSPARENT, so \"tentative\" has no form",
    "attendeeRelationship": "`ROLE`, but performer and speaker have no iCalendar equivalent",
    "attendeeType": "`CUTYPE`, but the provider stores no room/individual distinction",
    "minutes": "`VALARM;TRIGGER`, except the provider's \"use the system default\" (-1)",
    "method": "`VALARM;ACTION`, which has DISPLAY / EMAIL / AUDIO but no SMS",
    "eventColor": "RFC 7986 `COLOR` is a calendar-level CSS3 name, not a per-event ARGB integer",
    "calendar_displayName": "RFC 7986 `NAME`, or the de-facto `X-WR-CALNAME`; most exporters ignore both",
    "calendar_color": "RFC 7986 `COLOR`, a CSS3 name rather than an ARGB integer",
    "calendar_timezone": "a `VTIMEZONE` block or the de-facto `X-WR-TIMEZONE`",
    "name": "the de-facto `X-WR-CALNAME`",
}


def ics_for(table, column):
    if column in ICS_YES:
        return "yes — " + ICS_YES[column]
    if column in ICS_PARTIAL:
        return "partial — " + ICS_PARTIAL[column]
    if table == "ExtendedProperties" and column in ("name", "value"):
        return "partial — an exporter could emit these as `X-` properties, but the format has no notion of them"
    if table == "Colors" and column == "color":
        return "partial — RFC 7986 `COLOR` is a CSS3 colour name, not an ARGB integer"
    if column == "exrule":
        return "**no** — `EXRULE` existed in RFC 2445 but was removed in RFC 5545"
    if table == "Events" and column == "calendar_id":
        return "**no** — an iCalendar object has no addressable calendar; grouping is per file"
    return "**no**"


def verdict(table, column):
    if column in REMAPPED:
        return "remapped", REMAPPED[column]
    if table == "Events":
        if column in ("customAppPackage", "customAppUri"):
            return "no", R_ACL
        if column == "original_sync_id":
            return "no", R_IDENT
        if column in COLOR_KEY:
            return "no", R_COLORKEY
        if column in SYNC_ONLY or column in ALWAYS_DROP:
            return "no", R_SYNC
        if column in DERIVED_REASONS:
            return "derived", DERIVED_REASONS[column]
        if column in DERIVED:
            return "no", R_DERIVED
        if column in PROVIDER_OWNED:
            return "no", R_OWNED
        if column in VERIFY_EVENT_FIELDS:
            return "yes", "—"
        raise SystemExit(f"ColumnPolicy says nothing about the Events column '{column}'")
    if table == "Calendars":
        if column in SYNC_ONLY or column.startswith("cal_sync") or column in ("deleted", "canPartiallyUpdate"):
            return "no", R_SYNC
        if column in ("account_name", "account_type"):
            return "no", R_ACCT
        return "no", R_CAL
    if table == "Attendees":
        if column in ("attendeeIdentity", "attendeeIdNamespace"):
            return "no", R_IDENT
        return ("yes", "—") if column in VERIFY_ATTENDEE_FIELDS else ("no", "—")
    if table == "Reminders":
        return ("yes", "—") if column in VERIFY_REMINDER_FIELDS else ("no", "—")
    if table == "ExtendedProperties":
        return "no", R_EXT
    if table == "Colors":
        return "no", R_COLORS
    return "no", "—"


DESCRIPTIONS = {
    "calendar_id": "which calendar the event belongs to",
    "method": "the reminder method: alert / email / sms / default",
    "eventColor": "a per-event colour that overrides the calendar colour",
    "eventTimezone": "the event's time zone, stored separately from the UTC start instant",
    "description": "the event notes",
    "title": "the event title",
    "displayColor": "the colour an app should draw: the event colour, falling back to the calendar colour",
    "selfAttendeeStatus": "copy of the owner's attendee status",
    "eventColor_index": "key into the Colors table for a per-event colour",
    "calendar_color_index": "key into the Colors table for the calendar colour",
    "isOrganizer": "whether the user organises this event",
    "canInviteOthers": "whether the user may invite others",
    "hasAlarm": "whether the event has a reminder",
    "hasAttendeeData": "whether full guest data is present",
    "hasExtendedProperties": "whether the event has extended properties",
    "lastDate": "the last date the recurrence repeats on",
    "lastSynced": "pre-edit copy marker",
    "original_id": "the source event this override replaces",
    "original_sync_id": "the source series' sync id",
    "originalAllDay": "all-day flag of the source series",
    "uid2445": "iCalendar UID for events that arrived from an .ics file",
    "dirty": "whether the row has unsynced local changes",
    "mutators": "which packages wrote those local changes",
    "deleted": "whether the row is a pending deletion",
    "_sync_id": "the id the sync source assigned to this row",
    "canPartiallyUpdate": "whether edits keep a pre-edit copy for the adapter",
    "visible": "whether the calendar is shown",
    "sync_events": "whether the calendar is synced",
    "isPrimary": "whether this is the account's primary calendar",
    "ownerAccount": "the account that owns the calendar",
    "maxReminders": "how many reminders the calendar allows",
    "allowedReminders": "reminder methods the calendar allows",
    "allowedAvailability": "availability values the calendar allows",
    "allowedAttendeeTypes": "guest types the calendar allows",
    "canModifyTimeZone": "whether the user may change the calendar time zone",
    "canOrganizerRespond": "whether the user may reply as organiser",
    "calendar_access_level": "the user's access level for the calendar",
    "calendar_timezone": "the calendar's default time zone",
    "calendar_displayName": "the calendar's display name",
    "calendar_location": "geographic location of the calendar",
    "calendar_color": "the calendar's colour",
    "name": "calendar name used by the sync adapter",
    "account_name": "account the row was synced from",
    "account_type": "type of that account",
    "attendeeIdentity": "identity of the guest in the source account",
    "attendeeIdNamespace": "namespace that identity belongs to",
    "color_type": "whether the colour is for a calendar or an event",
    "color_index": "colour key",
    "color": "8-bit ARGB colour",
    "data": "opaque sync data",
    "value": "the property value",
    "attendeeRelationship": "guest / organiser",
    "attendeeType": "required / optional / resource",
    "attendeeStatus": "the guest's reply",
}
for index in range(1, 11):
    DESCRIPTIONS[f"cal_sync{index}"] = "sync adapter scratch space"
    DESCRIPTIONS[f"sync_data{index}"] = "sync adapter scratch space"


def describe(column, doc):
    if column in DESCRIPTIONS:
        return DESCRIPTIONS[column]
    # Javadoc continuation lines start with an asterisk, which would otherwise end up mid-sentence.
    doc = re.sub(r"\n\s*\*\s*", " ", doc)
    text = re.sub(r"\s+", " ", doc.lstrip("* ")).strip()
    text = re.sub(r"\{@link\s*([^}]*)\}", r"\1", text).split(" Type:")[0]
    text = re.sub(r"^The ", "", text).split(". ")[0].rstrip(".")
    return (text[0].upper() + text[1:]) if text else "provider column"


def table(title, blurb, rows, provider_table):
    out = [f"#### `{title}`", "", blurb, "",
           "| Field | Description | Preserved | Reason when not preserved | Supported by ICS |",
           "|---|---|---|---|---|"]
    for column, doc in rows:
        preserved, reason = verdict(provider_table, column)
        out.append(
            f"| `{column}` | {describe(column, doc)} | **{preserved}** | {reason} | "
            f"{ics_for(provider_table, column)} |"
        )
    out.append("")
    return out


def build(schema):
    events = ([(c, d) for c, d in schema["EventsColumns"]]
              + [(c, d) for c, d in schema["SyncColumns"]]
              + [(c, d) for c, d in schema["CalendarSyncColumns"]])
    calendars = [("_id", "row id")]
    calendars += [(c, d) for c, d in schema["SyncColumns"]]
    calendars += [(c, d) for c, d in schema["CalendarSyncColumns"]]
    calendars += [(c, d) for c, d in schema["CalendarColumns"]]
    calendars += [("name", "calendar name used by the sync adapter"),
                  ("calendar_location", "geographic location of the calendar")]
    attendees = [("_id", "row id")] + [(c, d) for c, d in schema["AttendeesColumns"]] + [
        ("attendeeRelationship", ""), ("attendeeType", ""), ("attendeeStatus", ""),
        ("attendeeIdentity", ""), ("attendeeIdNamespace", ""),
    ]
    reminders = [("_id", "row id")] + [(c, d) for c, d in schema["RemindersColumns"]]
    extended = [("_id", "row id")] + [(c, d) for c, d in schema["ExtendedPropertiesColumns"]]
    colors = [("_id", "row id"), ("account_name", ""), ("account_type", ""), ("data", "")] + \
             [(c, d) for c, d in schema["ColorsColumns"]]

    lines = [
        "Every column of every table the app reads. *Preserved* says what happens to the value on",
        "the destination:",
        "",
        "- **yes** — written from the backup.",
        "- **remapped** — not carried verbatim because it identifies a row the destination provider",
        "  owns; the provider assigns the equivalent itself.",
        "- **derived** — not written, but recomputed by the destination provider from data that *is*",
        "  copied, so the information survives even though the column does not.",
        "- **no** — not reproduced at all; the reason column says why.",
        "",
        "*Supported by ICS* is there for comparison with an ICS-based transfer: **yes** means an",
        "RFC 5545 property carries the value, **partial** means it is carried with a caveat, and",
        "**no** means iCalendar has no equivalent at all.",
        "",
    ]
    lines += table("Calendars",
                   "The destination calendar already exists, so nothing in this table is written. "
                   "Names and colours are still captured in the backup: they label the import "
                   "preview, and they are what the Calendars columns on each event row refer to.",
                   calendars, "Calendars")
    lines += table("Events",
                   "One row per event or recurring series. The provider surfaces the sync columns "
                   "on this row too and joins in the calendar attributes listed above, which is why "
                   "writing a dumped row straight back fails.",
                   events, "Events")
    lines += table("Attendees", "One row per guest per event.", attendees, "Attendees")
    lines += table("Reminders", "One row per reminder per event.", reminders, "Reminders")
    lines += table("ExtendedProperties", "Sync adapters' private scratch space.", extended,
                   "ExtendedProperties")
    lines += table("Colors",
                   "The per-account colour palette that the `*_color_index` columns point into.",
                   colors, "Colors")
    return "\n".join(lines).rstrip() + "\n"


def main():
    content = build(extract_schema())
    document = DOC.read_text(encoding="utf-8")
    start = document.index(BEGIN) + len(BEGIN)
    stop = document.index(END)
    DOC.write_text(document[:start] + "\n" + content + "\n" + document[stop:], encoding="utf-8")
    fields = sum(1 for line in content.splitlines() if line.startswith("| `"))
    print(f"wrote {fields} field rows into {DOC.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
