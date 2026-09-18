package dev.calendar.exportimport

/**
 * Column names and write restrictions, taken from AOSP
 * packages/providers/CalendarProvider (CalendarProvider2.java, CalendarDatabaseHelper.java)
 * and frameworks/base .../provider/CalendarContract.java.
 *
 * Every set below is a literal list of provider column names; they are not compile-time
 * constants in the platform API for the @hide members, so they are spelled out here.
 */
object Events {
    const val _ID = "_id"
    const val CALENDAR_ID = "calendar_id"
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val EVENT_LOCATION = "eventLocation"
    const val EVENT_COLOR = "eventColor"
    const val EVENT_COLOR_KEY = "eventColor_index"
    const val DISPLAY_COLOR = "displayColor"
    const val STATUS = "eventStatus"
    const val SELF_ATTENDEE_STATUS = "selfAttendeeStatus"
    const val DTSTART = "dtstart"
    const val DTEND = "dtend"
    const val DURATION = "duration"
    const val EVENT_TIMEZONE = "eventTimezone"
    const val EVENT_END_TIMEZONE = "eventEndTimezone"
    const val ALL_DAY = "allDay"
    const val ACCESS_LEVEL = "accessLevel"
    const val AVAILABILITY = "availability"
    const val HAS_ALARM = "hasAlarm"
    const val HAS_EXTENDED_PROPERTIES = "hasExtendedProperties"
    const val HAS_ATTENDEE_DATA = "hasAttendeeData"
    const val RRULE = "rrule"
    const val RDATE = "rdate"
    const val EXRULE = "exrule"
    const val EXDATE = "exdate"
    const val ORIGINAL_ID = "original_id"
    const val ORIGINAL_SYNC_ID = "original_sync_id"
    const val ORIGINAL_INSTANCE_TIME = "originalInstanceTime"
    const val ORIGINAL_ALL_DAY = "originalAllDay"
    const val LAST_DATE = "lastDate"
    const val GUESTS_CAN_MODIFY = "guestsCanModify"
    const val GUESTS_CAN_INVITE_OTHERS = "guestsCanInviteOthers"
    const val GUESTS_CAN_SEE_GUESTS = "guestsCanSeeGuests"
    const val ORGANIZER = "organizer"
    const val IS_ORGANIZER = "isOrganizer"
    const val CAN_INVITE_OTHERS = "canInviteOthers"
    const val CUSTOM_APP_PACKAGE = "customAppPackage"
    const val CUSTOM_APP_URI = "customAppUri"
    const val UID_2445 = "uid2445"
    const val DELETED = "deleted"
    const val DIRTY = "dirty"
    const val MUTATORS = "mutators"
    const val LAST_SYNCED = "lastSynced"
    const val _SYNC_ID = "_sync_id"
    const val SYNC_DATA1 = "sync_data1"
}

object Calendars {
    const val _ID = "_id"
    const val ACCOUNT_NAME = "account_name"
    const val ACCOUNT_TYPE = "account_type"
    const val DISPLAY_NAME = "calendar_displayName"
    const val CALENDAR_COLOR = "calendar_color"
    const val ACCESS_LEVEL = "calendar_access_level"
    const val DELETED = "deleted"

    /** Calendars.CAL_ACCESS_CONTRIBUTOR — the lowest level that may write events. */
    const val CAL_ACCESS_CONTRIBUTOR = 500
}

object Attendees {
    const val _ID = "_id"
    const val EVENT_ID = "event_id"
    const val NAME = "attendeeName"
    const val EMAIL = "attendeeEmail"
    const val RELATIONSHIP = "attendeeRelationship"
    const val TYPE = "attendeeType"
    const val STATUS = "attendeeStatus"
    const val IDENTITY = "attendeeIdentity"
    const val ID_NAMESPACE = "attendeeIdNamespace"
}

object Reminders {
    const val _ID = "_id"
    const val EVENT_ID = "event_id"
    const val MINUTES = "minutes"
    const val METHOD = "method"
}

object ColumnPolicy {

    /** Generic columns that are never part of the payload. */
    val ALWAYS_DROP = setOf("_id", "deleted", "dirty", "mutators")

    /**
     * Events columns that the provider refuses to let anyone write; they belong to the
     * calendar and are surfaced through the Events view join.
     * Source: CalendarContract.Events.PROVIDER_WRITABLE_COLUMNS.
     */
    val PROVIDER_OWNED_EVENT_COLUMNS = setOf(
        "account_name", "account_type",
        "cal_sync1", "cal_sync2", "cal_sync3", "cal_sync4", "cal_sync5",
        "cal_sync6", "cal_sync7", "cal_sync8", "cal_sync9", "cal_sync10",
        "allowedReminders", "allowedAttendeeTypes", "allowedAvailability",
        "calendar_access_level", "calendar_color", "calendar_color_index",
        "calendar_timezone", "calendar_displayName", "calendar_location",
        "canModifyTimeZone", "canOrganizerRespond", "canPartiallyUpdate",
        "sync_events", "visible", "isPrimary", "ownerAccount", "maxReminders"
    )

    /**
     * Events columns the importer must not write: the provider computes these, so writing them
     * would throw or be overwritten, except hasExtendedProperties, which only describes a table
     * that cannot be copied at all.
     */
    val NOT_WRITTEN_EVENT_COLUMNS = setOf(
        "lastDate", "lastSynced", "displayColor", "hasAlarm", "hasExtendedProperties",
        "canInviteOthers", "original_id", "originalAllDay"
    )

    /**
     * Fields that describe the *source* device, account or containing app rather than the
     * event, and that must therefore not be carried over verbatim:
     *
     *  - selfAttendeeStatus is the source user's own RSVP. On the destination the user is a
     *    different account, so replaying "accepted" or "declined" would assert a reply that
     *    was never made. The provider rebuilds this column from the attendee rows.
     *  - original_sync_id identifies the parent series inside the *source* account's sync
     *    namespace and is meaningless once the destination provider has reassigned ids.
     *
     * Two other things are kept deliberately. UID_2445 is an iCalendar UID, designed to travel
     * with the event, and it is what makes re-imports idempotent. customAppPackage / customAppUri
     * point at the app that owns the event's richer UI through ACTION_HANDLE_CUSTOM_EVENT; the
     * URI is opaque to everything but that app, so the link may dangle on the destination, but
     * that is a state the platform already tolerates (uninstall the app and the same dangling
     * pointer is left behind) and EventsEntity, the platform's own event-copy helper, carries the
     * pair. Copying it can only restore a link that would otherwise be lost.
     */
    val NOT_COPIED_AS_IS = setOf(
        "selfAttendeeStatus", "original_sync_id"
    )

    /**
     * Events columns only a sync adapter may write.
     * Source: CalendarContract.Events.SYNC_WRITABLE_COLUMNS.
     */
    val SYNC_ONLY_EVENT_COLUMNS = setOf(
        "_sync_id", "dirty", "mutators",
        "sync_data1", "sync_data2", "sync_data3", "sync_data4", "sync_data5",
        "sync_data6", "sync_data7", "sync_data8", "sync_data9", "sync_data10"
    )

    /**
     * Color *keys* are never written: an unresolvable key makes Calendars insert throw
     * (verifyColorExists) and makes the event colour trigger silently null eventColor.
     * Only the literal colour integers are written.
     */
    val COLOR_KEY_COLUMNS = setOf("calendar_color_index", "eventColor_index")

    /**
     * The only columns accepted when creating a recurrence exception.
     * Source: CalendarProvider2.ALLOWED_IN_EXCEPTION.
     */
    val ALLOWED_IN_EXCEPTION = setOf(
        "_sync_id", "sync_data1", "sync_data3", "sync_data6", "sync_data7",
        "title", "eventLocation", "description", "eventColor", "eventColor_index",
        "eventStatus", "selfAttendeeStatus", "dtstart", "eventTimezone",
        "eventEndTimezone", "duration", "allDay", "accessLevel", "availability",
        "hasAlarm", "hasExtendedProperties", "rrule", "rdate", "exrule", "exdate",
        "original_sync_id", "originalInstanceTime", "hasAttendeeData",
        "guestsCanModify", "guestsCanInviteOthers", "guestsCanSeeGuests",
        "organizer", "customAppPackage", "customAppUri", "uid2445"
    )

    /** Event fields compared by the verifier; everything here must survive a round trip. */
    val VERIFY_EVENT_FIELDS = listOf(
        Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART,
        Events.DTEND, Events.DURATION, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
        Events.ALL_DAY, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE,
        Events.STATUS, Events.AVAILABILITY, Events.ACCESS_LEVEL, Events.ORGANIZER,
        Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS,
        Events.GUESTS_CAN_SEE_GUESTS, Events.UID_2445, Events.EVENT_COLOR,
        // Only set on recurrence overrides, where it is the thing that keeps the edit attached
        // to the right occurrence.
        Events.ORIGINAL_INSTANCE_TIME,
        // Opaque to us, but part of the event's payload: see NOT_COPIED_AS_IS above.
        Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI,
        // Sync-supplied, not provider-computed.
        Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER
    )

    val VERIFY_ATTENDEE_FIELDS = listOf(
        Attendees.NAME, Attendees.EMAIL, Attendees.RELATIONSHIP, Attendees.TYPE,
        Attendees.STATUS
    )

    val VERIFY_REMINDER_FIELDS = listOf(Reminders.MINUTES, Reminders.METHOD)

    /**
     * Fields the verifier deliberately ignores, with the reason. Printed in the report so
     * nothing is quietly skipped.
     */
    val EXPECTED_LOSS: List<Pair<String, String>> = listOf(
        "_id / calendar_id / original_id" to "local row identity, reassigned by the provider",
        "dirty / mutators / lastSynced" to "sync bookkeeping, set by the provider",
        "_sync_id / sync_data1..10 / cal_sync1..10" to "owned by the account's sync adapter",
        "lastDate / displayColor / hasAlarm / canInviteOthers" to "computed by the provider",
        "hasExtendedProperties" to "describes the ExtendedProperties table, which is not copied",
        "selfAttendeeStatus" to "copied from the attendee rows by the provider",
        "original_sync_id / originalAllDay" to "recurrence bookkeeping derived from the parent",
        "attendeeIdentity / attendeeIdNamespace" to
            "the source account's identity namespace, meaningless for the destination user",
        "calendar_displayName / calendar_color / visible / calendar_access_level and the rest of Calendars" to
            "the destination calendar already exists and keeps its own metadata",
        "Colors and ExtendedProperties tables" to "writable only by a sync adapter, which this app is not",
        "uid2445 / eventColor / guestsCan* / customAppUri" to
            "an account's sync adapter may rewrite or drop columns it does not model after upload"
    )
}
