# Android Glider Logbook: Specification

Oct 18, 2026 · @Peter

## Purpose and scope

This is a personal glider flight log for Android that records what was flown, where, when and for how long. Data lives on the device, is exportable in open formats, and must stay responsive with 10,000 flights.

**In scope**

- Form-based flight entry, manual or pre-filled from an IGC file.
- User-maintained airfield, glider and club records, and a pilot profile (name and licence or membership numbers).
- Flexible filtering, personal totals by role, and an aircraft-logbook calculator.
- CSV export and import, and full JSON backup and restore.

**Out of scope**

- Computed aircraft airframe hours or launch counts. A personal log can't know them for club, loaned or syndicate gliders. The glider record holds only the last total the pilot wrote in the aircraft logbook, as a calculator default.
- iOS, SQLite-file backups, and any Auto Backup configuration (left at the Android default).
- Required launch and landing times. A flight records its date and duration. Launch and landing times, and the landing place, are optional fields behind two Settings that are off by default.

## Platform and stack

The app is Android-only: native Kotlin with Jetpack Compose and a Room (SQLite) database. A full Kotlin Multiplatform project is rejected because of its extra build and learning cost.

- **Port-friendly choices that cost nothing:** Room, which is itself multiplatform-capable since 2.7.0, and Compose, which Compose Multiplatform builds on.
- **Core logic in its own package:** The IGC parser, duration arithmetic and CSV and JSON handling use no `android.*` imports, so they are testable on a plain JVM.
- **HTTP behind one interface:** There are only a few calls (WeGlide, later OGN), so use whichever client is familiar.
- **Performance:** Indexes on date, airfield, glider and `log_no`, with filtering, sorting and totals done in SQL, and a paged list. 10,000 rows is small for SQLite.
- **Data portability:** A documented, versioned JSON schema is the main aid to anyone porting to iOS later.

## Data model

The model is normalised: flights refer to gliders, airfields and clubs by ID, and every record has a UUID and an `updated_at`.

| Table | Fields | Rules |
| --- | --- | --- |
| Flight | log\_no, date, duration\_minutes, glider, airfield, club, launch\_type, role, pic\_name, notes, source, import\_batch\_id, plus optional launch\_time, landing\_time, landing\_airfield and landing\_place | `log_no` is required, unique and positive. `date` is the local flight date. Duration is whole minutes. Role is one of PIC, STUDENT, INSTRUCTOR, COACH, MUTUAL. Launch type is mandatory. Club is stored whenever the airfield has at least one club. The optional fields appear in the form only when their Settings are on. Times are local 24-hour HH:MM, and a landing time must be after the launch time. A landing airfield and a landing place are never both set, and neither set means the same place as departure. The pic\_name is required, non-blank text. |
| Glider | registration, type, weglide\_aircraft\_id, logbook\_total\_minutes, logbook\_total\_flights, logbook\_total\_date, incomplete, archived | Registration is required and unique, stored as written; matching ignores case, spaces and hyphens, and no format is enforced (`?` and `B4` exist). Type is free text, as the paper logbook shows it. The WeGlide aircraft ID is set per glider by the pilot from WeGlide's aircraft list, and WeGlide upload is blocked until it is set. Choosing an entry also replaces the type with WeGlide's name; with no pick, the original type stays. No make or class. The logbook total fields hold only the last total the pilot wrote in the aircraft logbook, as a default for the calculator, never a computed airframe total. |
| Airfield | name, latitude, longitude, match radius, incomplete, archived | Name is required and unique ignoring case. Coordinates are optional but needed for IGC matching. The match radius has a default and can be set per airfield. |
| Club, airfield-club link | club name; airfield and club IDs | Many-to-many, because an airfield can host several clubs and a pilot can fly with several at one field. |
| Launch type | name | Seeded with Aero, Winch, Self and Auto, and extendable. CSV values must match a name. |
| Opening balance | role bucket, as-of date, minutes, flights | One per role, entered once from the last paper page. |

Deleted records are kept as tombstones so a JSON Merge respects deletions, and the unique index on `log_no` ignores them. Non-secret settings are part of the JSON backup. WeGlide credentials live in encrypted storage and are never exported.

## Flight entry, roles and log numbers

The flight form has dropdowns for glider, airfield and launch type, a role choice, a duration and notes. Every flight lands in exactly one role bucket, so Combined is the sum of all five.

- **Role:** PIC, or Dual with a second choice of Student, Instructor, Coach or Mutual.
- **Duration:** entered as H:MM. Overflow is accepted (1:75 becomes 2:15) and the value is stored as whole minutes.
- **Club:** the dropdown appears only when the chosen airfield has more than one club. With exactly one, it is set silently.
- **Dropdowns:** glider rows read like `FQM, Hornet`, because several types repeat and only the registration tells them apart.

**Pilot in command**

Every flight records who was pilot in command, as required text.

- **Default:** the name from the pilot profile when the role is PIC, Instructor or Coach, and the pilot can overwrite it.
- **Student and Mutual:** the field starts blank, and another person's name must be entered before the flight can be saved.
- **Changing the role:** switching to Student or Mutual clears the field if it still holds the profile name, and switching to PIC, Instructor or Coach fills a blank field with the profile name. A name the pilot typed is never replaced.
- **No profile name yet:** the field is blank and the name is typed on each flight. The app never offers to save it to the profile.
- **History:** the name is copied onto the flight when it is saved, so later changes to the profile do not alter past flights.

**Optional fields**

Two Settings, both off by default, add optional fields to the flight form. Turning one off hides its fields and keeps any data already entered, and neither Setting makes its fields required.

- **Record landing place:** a Landing place choice that defaults to Same as departure, with an airfield dropdown and an Other place text box for outlandings. Regulators such as EASA and the FAA expect a pilot's logbook to show where each flight ended.
- **Record launch and landing times:** two HH:MM fields in local 24-hour time. When both are entered, the duration is pre-filled from them and can still be edited, and the form warns if the two disagree by more than a minute.

**Log numbers**

- `log_no` is one continuous sequence matching the paper logbook, required and never blank.
- The form pre-fills the highest existing number plus one, read with an indexed `MAX` query, never from a stored counter. The first ever flight pre-fills 1 and the pilot types the real number once.
- Duplicates are blocked with a message naming the flight that holds the number. Deleting the highest-numbered flight frees its number.
- To swap or correct numbers, give one flight the next free higher number, fix the other, then renumber the first.

## Pilot profile

The pilot's name and identifying numbers are recorded once, in Settings, and are saved in the JSON backup. The name is the default pilot in command on new flights. The profile changes only when the pilot edits it deliberately in Settings. No flight entry, import, Merge, upload or other process alters it, and only a Restore into an empty install loads it from a backup.

- **Name:** free text.
- **Identifiers:** any number of label and value pairs, for example Licence number, Membership number or ARN. Labels are free text with a few suggestions, so the app is not tied to one country's scheme.
- **Kept apart from WeGlide:** the WeGlide ID and date of birth stay in encrypted storage, are not part of the profile, and are never exported.
- **Privacy:** the profile stays on the device and is written only to the pilot's own backups. It is not sent anywhere.

## Screens, filters and totals

The home screen offers New flight and From IGC immediately, and navigation covers Flights, Totals, Airfields and Gliders, with Settings behind a top-bar icon. Every screen is a scrolling list or a form, apart from the Totals grid, and the aircraft logbook calculator is a pop-up sheet over the Flights list.

- **Home:** the two entry buttons, a compact totals card, and a "last backed up" line that nudges when stale. IGC files shared from other apps open the same import flow.
- **Flights:** newest first by date then `log_no`. Each flight is a two-line row: log number, date, glider and type on the first line (for example 340 · 17/10/26 · FQM Hornet) and airfield, launch type, role and duration on the second. Tapping a row opens the flight to edit, including notes, and the list is paged. Active filters show as chips above the list, and the filter button opens a bottom sheet with dropdowns for glider, airfield and club, plus date range, role and a log-number range, and a launch time range when the Times setting is on.
- **Totals:** a small grid, the one table-like screen, with a row each for PIC, Student, Instructor, Coach, Mutual and Combined and columns for hours and flights. With no filter active, each figure equals the opening balance plus the flights. With any filter active, opening balances are left out and only matching flights count, so "my hours in this glider at this airfield" is one query. They are labelled as the pilot's own time, never aircraft time.
- **Airfields and Gliders:** plain lists. A glider row shows the registration and type, a glider's detail also shows its logbook total as last entered, and an airfield row shows its name and club names. A plus button creates a record, tapping a row edits or archives it, and an "incomplete" badge marks records created by CSV import.
- **Settings:** pilot name and identifying numbers, hours display (H:MM or decimal), whether Instructor and Coach flights also count toward PIC in Totals, Record landing place, Record launch and landing times, opening balances, undo last import, backup folder and restore, and WeGlide credentials.

## Aircraft logbook calculator

The calculator adds a day's flying to the aircraft logbook total, for the legal aircraft logbook. It never touches the database totals. The glider record keeps the last logbook total the pilot wrote, as a default only, because others may fly the aircraft and the figure can drift from the real logbook.

1. The pilot confirms the day's flights.
2. In the Flights list, flights are grouped by date and glider, and each group has an "Add to aircraft logbook" button.
3. The calculator opens with the day's total hours as H:MM and the number of flights, both prefilled and editable, with a checklist of the included flights that updates both figures.
4. The logbook total fields are prefilled from the glider's stored values, with the date they apply to shown beside them. The pilot overwrites them when they differ from the aircraft logbook, for example after someone else flew the glider, and the calculator shows the new total.
5. For flying the app doesn't know about, the pilot adds extra H:MM and flight entries. The same calculator opens from the Gliders screen with the stored total and no flights, for fully manual use.

**Saving the total**

- **Save new total:** this button writes the result back to the glider record, with the date of the flying day just added. In manual use the pilot picks the date, defaulting to today. Closing the calculator without saving changes nothing.
- **Double-add warning:** if the stored date is on or after the day being added, the calculator warns that the day may already have been added, because adding the same day twice is the easiest way to corrupt the figure.
- **Gliders screen:** a glider's detail shows the stored figures, for example "Logbook total as last entered: 1234:56, 5678 flights, 18/10/26", and lets the pilot edit or clear them by hand. The flights count is optional and may be removed later.

The count is labelled "Flights", as everywhere in the app, because a flight is one landing. Role does not matter here, since airframe time counts for every role. Hours can be shown as H:MM or decimal, selectable in Settings, with rounding to the nearest minute.

## IGC import

An IGC file pre-fills the flight form and is then discarded: it is read from the file picker or a share action, nothing is stored or linked, and the same stream can be used once for an immediate WeGlide upload.

**Extraction**

- **Flying detection:** ground speed is derived from consecutive fixes, and flying begins when it stays above 50 km/h for a few seconds, the threshold Flightbook is reported to use. A height gain above the starting altitude is also required, so a logger left in a car is not a flight.
- **Several segments:** a file with more than one flying segment lists them, and each can be imported as its own flight.
- **Preview:** launch and landing are shown as read-only text for a sanity check, and are stored only when the Times setting is on.
- **Fields produced:** flight date, duration, airfield, glider, and the next log number, plus launch and landing times when the Times setting is on and the landing place when the Place setting is on. Launch type and role are left to the pilot, since both are mandatory.

**Airfield matching**

1. Take the launch fix and compute its distance to every airfield that has coordinates.
2. Pick the nearest within that airfield's match radius.
3. If several are in range, preselect the nearest and list the others with distances.
4. If none match, prompt "No airfield found near this launch point. Create one?" with the form pre-filled with the fix's coordinates and elevation. Cancelling leaves the field blank, and the form can't be saved until an existing airfield is chosen.

**Glider matching:** the registration in the header is matched against glider records, ignoring case, spaces and hyphens. The type text is free text and is not used.

The threshold and default radius should be tuned against a handful of the pilot's own IGC files.

## Timezones and the flight date

Times are stored as local clock times, so the only use for a timezone is converting an IGC file's UTC times into the correct local flight date and, when the Times setting is on, local launch and landing times. In Sydney, a flight launching before 11:00 in summer carries the previous UTC date.

Confirmed: drop the zone from airfields and flights entirely, and use the device's timezone for that one conversion. The form shows the date for the pilot to confirm or edit. The only failure case is importing a trip flight from another zone near midnight UTC, which is a one-tap date edit.

This also removes the scroll-and-search zone picker, the LatLongToTimezone dependency, and the country and zone data.

## LXNAV flight download (investigation)

This is an investigation, not a build step. Minimal testing has been done without success, so nothing beyond the prototype described below should be built until it has worked.

**Goal:** download an IGC flight log from an LXNAV logger over Bluetooth, feed it into the IGC import flow, and optionally upload it to WeGlide, so the pilot needs neither a cable nor a separate app to move the file. The app analyses the file itself to pre-fill the form, and WeGlide receives the same bytes for its own analysis.

1. The pilot pairs the logger in Android's Bluetooth settings and chooses From logger on the Home screen.
2. The app lists the paired devices, then reads the chosen logger's logbook: for each IGC file, its name, date, the start and end times the logger recorded, and its size.
3. The pilot picks a flight. The app downloads the file to a temporary file in app-private storage, byte for byte, and checks it against the expected size and the checksums.
4. The file goes into the normal IGC import flow, and the optional WeGlide upload uses the same bytes.
5. The temporary file is deleted when the import or upload finishes or is cancelled.

**What the DataPort specification says**

The LXNAV Data Port Specification, version 1.04 dated 2022-07-19 (66 pages), is marked Draft and Internal, so it is only unofficially available and may be out of date. The points below were checked against its text.

- **Scope:** serial or Bluetooth communication with Nano and Nano3 devices, version 3 protocol only. Nano4, S100 and newer devices are not covered.
- **Framing:** each sentence starts with `$`, ends with `*` followed by a two-digit hexadecimal XOR checksum of the characters between them, then CR LF. Fields are comma separated and a sentence has at most 256 characters. The specification's examples omit the `$`, the checksum and the line ending, so they are not what goes over the link.
- **Listing:** `PLXVC,LOGBOOKSIZE,R` returns the number of IGC files in the root folder. `PLXVC,LOGBOOK,R,<start>,<end>` returns, for each flight, its number, the total, the file name, the date (DD.MM.YYYY), the start and end times (HH:MM:SS, or --:--:--) and the size in bytes. The end index is exclusive.
- **Download:** there are two methods. The older `PLXVC,FLIGHT` reads the file line by line, recommending blocks of 7 lines. The newer one starts with `FILE_INFO` and receives base64 payloads of up to 100 bytes in blocks of up to 30, with a running CRC32 on each line. The client asks for the next block with `FILE_OK` and for a repeat with `FILE_DATA_LOST` or `FILE_CS_INVALID`, receives a whole-file CRC32 at the end, and closes with `FILE_CRC_OK`. Skipping the close can make the next download fail.
- **Bluetooth module:** `PLXVC,BT_INFO,R` returns -1 (none), 0 (BTM 182) or 1 (BM77). The BM77 can freeze on large transfers, so a block size of 1 is advised for it. The specification names the modules but does not say whether they use Classic Bluetooth or BLE.
- **Timing:** the device counts the lines of a file before it starts sending, which can take a long time for large files, so the client must keep waiting. Keep-alive messages are described as not yet implemented.
- **Never used:** the specification also describes deleting files and uploading files to the device. This app must do neither.

**XCSoar**

- XCSoar 7.44 (March 2026) lists an LXNAV integration overhaul with protocol 1.05 support and improved flight download, including for Nano devices. It credits LXNAV for sharing protocol documentation and a contributor for Nano download robustness, and XCSoar's documentation site has an LXNAV Protocol page.
- The XCSoar files seen so far carry GPL-2.0-or-later headers, so copying code would bring GPL obligations. Use it as a protocol reference, not a source to copy. This is not legal advice. The driver's source path is not verified.
- XCSoar can also serve as a workaround and a test oracle: it can download flights from supported LXNAV loggers, the saved IGC file can be shared into this app, and the same file gives a reference for a byte-for-byte comparison.

**Open questions (unverified)**

- Whether the Nano, Nano3, Nano4 and S100 use Bluetooth Classic (an RFCOMM serial link) or BLE. This decides the Android API, and BLE is the harder path. A Bluetooth scan and the Android Bluetooth HCI snoop log can show which.
- Whether the documented commands work on the S100 and Nano4, and which protocol version each reports. The specification in hand is 1.04, and XCSoar supports 1.05.
- Whether Nano Config uses commands beyond the documented ones. That would matter for settings, declarations and firmware, not for IGC download.
- Whether Nano4 IGC files are signed, so that any change would invalidate them. This is reported but not verified.

**Test results**

- **2026-10-19, S100 over Bluetooth:** four sentences were sent from a Bluetooth serial terminal (INFO, LOGBOOKSIZE, LOGBOOK 1 to 5 and BT\_INFO) and the S100 gave no response. This does not yet show that the S100 lacks the feature. The specification covers Nano and Nano3 only, and the terminal's line ending and the state of the link were not independently confirmed. LXNAV's own S10 specification page, and a retailer's S100 page, both state that flights are downloadable through Bluetooth, so the capability exists even if these sentences are not how it is done.
- **Checksum helper:** the shell function must be given the sentence body in single quotes, without the `$` or the checksum, for example `cs 'PLXVC,LOGBOOKSIZE,R'`, because an unquoted `$PLXVC` is expanded by the shell. The correct output for that call is `$PLXVC,LOGBOOKSIZE,R*4B`.

**Design constraints**

- **Paired devices only:** the pilot pairs the logger in Android's Bluetooth settings, and the app lists the already-paired devices without scanning. Android's documentation says an app that communicates with paired devices needs the `BLUETOOTH_CONNECT` permission, a runtime permission on Android 12 and later, and that scanning for devices additionally needs `BLUETOOTH_SCAN`. The legacy Bluetooth permissions apply only up to Android 11. The permission is requested the first time the pilot uses From logger.
- **Layering:** the protocol code (sentence framing, checksums, CRC32, block handling and retries) sits in the `core` package behind a transport interface with a fake implementation, so it can be tested without hardware. The Android Bluetooth transport sits in `data`.
- **Files:** an IGC file is stored byte for byte and is never re-encoded, trimmed or converted. It lives in a private temporary file, is checked against the size from the logbook and the CRC32, and is deleted after use.
- **Reliability:** timeouts that allow for the long line count, repeat requests for lost or corrupt lines, `FILE_CANCEL` to abandon a transfer, a block size of 1 for the BM77, and a clean restart after a failure, remembering that skipping `FILE_CRC_OK` can make the next download fail.
- **Read-only:** no deleting files, no uploading files, and no changing settings or declarations on the logger.

**Prototype plan**

1. Get a reference file by downloading a flight with XCSoar or another trusted tool, and record its size and a hash.
2. Pair the logger in Android settings. In a Bluetooth serial terminal app that can send CR LF, first check that the link carries data (the logger should stream NMEA sentences), then send the strings below and note any reply. Make sure no other app, such as XCSoar, is holding the connection. The checksums were computed by hand from the specification's rule, so recompute them with a tool if nothing comes back. Bare text without the `$`, the checksum and the line ending would not be recognised, which is one possible explanation for the earlier failed tests.

```
$PLXVC,LOGBOOKSIZE,R*4B
$PLXVC,BT_INFO,R*44
$PLXVC,INFO,R*0D
```

3. If there is no reply, capture the traffic of XCSoar or Nano Config with the Android Bluetooth HCI snoop log and read it in Wireshark, to find the framing and whether the link is Classic or BLE.
4. Build the minimal client: list the flights, then download one file with `FILE_INFO`, checking the CRCs, and compare its size and hash with the reference file.
5. Only then connect it to the IGC import flow as From logger.
6. Optionally ask LXNAV for the current specification.

The prototype is done when the flight list matches what the logger shows, a downloaded file has the same size and hash as the reference, an interrupted transfer recovers or fails cleanly, and the file opens in the IGC import flow.

## WeGlide upload

Upload is offered from the IGC import flow using the file the pilot just picked, with no copy kept. This is planned for a later phase.

- **Precedent:** WeGlide lists XCSoar among the apps that upload directly, and XCSoar has supported WeGlide IGC upload since version 7.23. XCSoar's WeGlide settings ask for a WeGlide ID and a date of birth, which probably explains its low-friction upload.
- **Credentials:** the WeGlide ID (sent as `user_id`) and date of birth (sent as `date_of_birth`, yyyy-mm-dd) are kept in encrypted storage and excluded from all exports. If missing or rejected, the app asks the pilot to re-enter them.
- **Request:** a multipart POST to `/v1/igcfile` on the WeGlide API, with a `user-agent` header and the body fields `user_id`, `date_of_birth`, `aircraft_id` and `file`. This matches what XCSoar sends. WeGlide describes its API as not finalised and may block non-residential IPs, so the app must handle failures gracefully.
- **Aircraft ID:** each glider stores a `weglide_aircraft_id`, which the pilot sets by searching WeGlide's aircraft list (`GET /v1/aircraft`, with `id`, `name`, `kind`, `sc_class` and `dmst_index` per entry). The app caches the list, searches it ignoring case, spaces and hyphens, and shows kind and class to tell variants apart. Upload is blocked until a glider has an ID, because WeGlide's own guess from the free-text type in the IGC header can pick the wrong variant. Choosing an entry also replaces the glider's type with WeGlide's name, and with no pick the original type stays. The pick sheet shows a line such as "Type will change from ASK21 to ASK 21" before saving, and the original text is lost once saved, though the type stays editable by hand. Clearing the ID later does not restore the old type, and a later rename of the entry on WeGlide does not change the glider's name until the pilot picks again.

## CSV export and import

CSV is for spreadsheets and other systems, not for backup. The app exports flights, gliders and airfields as three files, and import is deliberately strict: the user supplies compliant data or the import fails.

**Flights import**

- **Required columns:** `date` (YYYY-MM-DD), `glider` (registration), `duration` (H:MM or whole minutes), `airfield` and `role` (PIC, STUDENT, INSTRUCTOR, COACH or MUTUAL).
- **Launch type:** expected as `launch_type`. If the column is missing or some cells are blank, the import screen asks once for a default for those rows. An unknown non-blank value fails with the list of valid names.
- **Log number:** if a `log_no` column is present, every cell must be a valid, unused positive integer. If it is absent, the screen offers once to number the rows from the current maximum plus one in file order.
- **Optional columns:** `club` and `notes`.
- **Validation:** the whole file is checked first, every error is reported with its row number, and nothing is imported if any row fails. A summary is shown, then everything is committed in one transaction.

**Unmatched gliders and airfields**

- The confirmation screen lists them and creates minimal stub records: registration only for a glider, name only for an airfield. Stubs are flagged incomplete until the pilot fills them in.
- Each unmatched name has a "use existing…" dropdown to avoid near-duplicates such as "Mt Beauty" and "Mount Beauty".
- Every import is tagged with a batch ID, and Settings offers "Undo last import", which also removes that batch's unused stubs.

**Gliders and airfields files:** each has its own export and import (gliders: registration and type; airfields: name, coordinates and club names), so a fleet or field list can be loaded without parsing it from the log.

**Worked example:** the pilot's existing logbook CSV needs spreadsheet preparation first. Dates change from dd/mm/yy to YYYY-MM-DD, the Place column (for example `YMBT MBGC`) splits into airfield and club, the In Command values are milliseconds and divide by 60,000 to give minutes, and launch type names such as Winch and Aero must match the app's list.

## Backup and restore

A lost phone must not lose the logbook, so one JSON file is the only backup and restore format. There are no SQLite file backups and no zip of CSVs.

- **Contents:** every table, including archived records, tombstones, opening balances and non-secret settings. WeGlide credentials are never included.
- **Manifest:** a format name, a format version independent of the Room schema version, the export time and the app version.
- **Restore:** replaces everything in an empty install. **Merge** upserts by UUID with the newest `updated_at` winning, and a `log_no` collision is reported and nothing is committed until it is resolved.
- **Safety:** import runs in one transaction in dependency order, validates references, enum values and durations first, and shows a summary before committing. A file from a newer format version is refused, and an older one is upgraded.
- **Off-phone copies:** the pilot picks a folder once, which can be cloud-synced, and the app writes timestamped JSON exports there on a schedule or after a set number of changes. Names are unique because the file-create picker can't overwrite, and the last N files are kept.
- **First run:** the first screen offers Start fresh or Restore from backup. Settings also offers restore.
- **Android Auto Backup:** left at its default. If a restored database already exists on first run, the app opens it normally. Room migrations cover every released schema version, and missing credentials are treated as a normal re-entry case.

**Test:** export, import into an empty database, export again, and compare the two files, which should be identical with stable ordering.

## Appendix A: Database schema

The appendices hold proposed defaults written while preparing this spec for implementation, and an implementer should ask before departing from them. Primary keys are UUID strings (except opening\_balance, keyed by role, and airfield\_club, with a composite key), dates are ISO text (YYYY-MM-DD), and instants are epoch milliseconds in UTC. Tables with a tombstone have `deleted_at`, null for live rows.

| Table | Columns | Constraints and indexes |
| --- | --- | --- |
| flight | id, log\_no (integer, required), date (text, required), duration\_minutes (integer, 1 to 1440), glider\_id, airfield\_id, club\_id (nullable), launch\_type\_id, role (text, required), pic\_name (text, required, non-blank), launch\_time (text HH:MM, nullable), landing\_time (text HH:MM, nullable), landing\_airfield\_id (nullable), landing\_place (text, nullable), notes (nullable), source (MANUAL, IGC or CSV), import\_batch\_id (nullable), updated\_at, deleted\_at | Unique index on `log_no` for live rows. Indexes on date, glider\_id, airfield\_id, club\_id, role and import\_batch\_id. Foreign keys restrict deletion. |
| glider | id, registration, registration\_key, type (text, empty allowed for stubs), weglide\_aircraft\_id (integer, nullable), logbook\_total\_minutes (integer, nullable), logbook\_total\_flights (integer, nullable), logbook\_total\_date (text YYYY-MM-DD, nullable), incomplete, archived, import\_batch\_id (nullable), updated\_at, deleted\_at | Unique index on `registration_key` for live rows. The key is the registration lower-cased with spaces and hyphens removed and nothing else changed, so `?` stays valid. The logbook total minutes and date are set together, and the flights count is optional and may be removed later. |
| airfield | id, name, name\_key, latitude, longitude, elevation\_m, match\_radius\_m (null means the default), incomplete, archived, import\_batch\_id (nullable), updated\_at, deleted\_at | Unique index on `name_key` (trimmed, lower-cased) for live rows. Incomplete means no coordinates. `elevation_m` is an addition to the data model, used to pre-fill a new airfield from an IGC file. |
| club | id, name, name\_key, updated\_at, deleted\_at | Unique index on `name_key` for live rows. |
| airfield\_club | airfield\_id, club\_id | Composite primary key. Rows are deleted outright when links change, and the airfield's `updated_at` is bumped, so a Merge treats the link set as part of the airfield. |
| launch\_type | id, name, name\_key, sort\_order, archived, updated\_at, deleted\_at | Seeded with Aero, Winch, Self and Auto using fixed UUIDs derived from the names, so a restore into a fresh install cannot duplicate them. |
| opening\_balance | role (primary key), as\_of\_date, minutes, flights, updated\_at | Exactly five rows, one per role, created with zeros on first run. |
| import\_batch | id, created\_at, file\_name, flight\_count | Used by Undo last import, which removes the flights, stubs and batch row. |

**Notes**

- Roles are stored as the text names PIC, STUDENT, INSTRUCTOR, COACH and MUTUAL.
- Room's index annotation, as far as I know, cannot express a WHERE clause. Create the partial unique indexes with raw SQL in the database-creation callback and in migrations, and test that they enforce uniqueness.
- Settings live in DataStore: `hours_display` (HM or DECIMAL), `instruction_counts_as_pic` (boolean), record\_landing\_place (boolean), record\_times (boolean), pilot\_name (text) and pilot\_identifiers (a list of label and value pairs) are backed up. `backup_folder_uri` and `last_backup_at` are specific to the device and are not.
- The WeGlide ID and date of birth are held in Keystore-backed encrypted storage. Check which AndroidX API is current for this before choosing one.
- Totals use `SUM(duration_minutes)` and `COUNT(*)` per role over live flights with the active filters. With no filter active, the opening balance is added. Combined counts each flight once, and when the Settings option that counts instruction as PIC is on, the PIC row also includes Instructor and Coach flights.

## Appendix B: JSON backup format

The backup is one UTF-8 JSON file whose top level holds the manifest fields and one array per table. Field names match the schema columns, with timestamps written as ISO 8601 UTC strings.

```
{
  "format": "glider-logbook-backup",
  "format_version": 1,
  "exported_at": "2026-10-18T09:47:00Z",
  "app_version": "1.0.0",
  "launch_types": [ ... ],
  "clubs": [ ... ],
  "airfields": [ { "id": "...", "name": "YMBT", "club_ids": ["...", "..."], ... } ],
  "gliders": [ ... ],
  "flights": [
    {
      "id": "...", "log_no": 340, "date": "2026-10-17",
      "duration_minutes": 271, "glider_id": "...", "airfield_id": "...",
      "club_id": "...", "launch_type_id": "...", "role": "PIC", "pic_name": "...",
      "notes": null, "source": "CSV", "import_batch_id": null,
      "updated_at": "2026-10-18T09:47:00Z", "deleted_at": null
    }
  ],
  "opening_balances": [ ... ],
  "import_batches": [ ... ],
  "settings": { "hours_display": "HM", "instruction_counts_as_pic": false }
}
```

- **Stable output:** arrays are sorted by `id` (opening balances by role), keys are written in a fixed order, and the file is indented with two spaces, so an export, import and export round trip produces identical files.
- **Contents:** archived records and tombstones are included. Each airfield carries its club IDs in place of the link table. Credentials and device-specific settings are excluded. Flight objects also carry launch\_time, landing\_time, landing\_airfield\_id and landing\_place (null when unused), and the settings object carries record\_landing\_place, record\_times, pilot\_name and pilot\_identifiers.
- **Version rules:** a file whose `format` differs from the name above is refused, a `format_version` newer than the app supports is refused with a clear message, and older versions are upgraded step by step. Unknown fields are ignored.
- **Restore and Merge:** Restore requires an empty database. Merge matches by `id`, the newer `updated_at` wins (opening balances match by role), tombstones are respected, and a `log_no` collision between different flights is listed and blocks the commit until resolved.

## Appendix C: CSV formats

All files are UTF-8 with a comma delimiter, a single header row, and RFC 4180 quoting (fields containing commas, quotes or line breaks are quoted, with inner quotes doubled). Export writes a UTF-8 byte order mark so spreadsheets read accented names correctly, and import accepts files with or without one. Header names are matched ignoring case and surrounding spaces, blank rows are skipped, and unlisted columns are ignored with a warning on the summary screen.

**flights.csv** (export column order: log\_no, date, glider, airfield, club, launch\_type, role, pic, duration, launch\_time, landing\_time, landing\_airfield, landing\_place, notes)

| Column | On import | Format |
| --- | --- | --- |
| log\_no | Optional | Positive integer. See the log number rules above. |
| date | Required | YYYY-MM-DD |
| glider | Required | Registration, matched ignoring case, spaces and hyphens. |
| duration | Required | H:MM (minutes may exceed 59, so 1:75 is valid) if it contains a colon, otherwise whole minutes. At least one minute. Export writes H:MM. |
| airfield | Required | Airfield name, matched ignoring case after trimming. |
| role | Required | PIC, STUDENT, INSTRUCTOR, COACH or MUTUAL |
| launch\_type | Prompted if missing or blank | A launch type name, matched ignoring case. |
| club | Optional | Club name. A club that doesn't exist is created and linked to the airfield. A blank cell leaves the club unset, even for an airfield with several clubs. |
| notes | Optional | Free text. |

**gliders.csv:** `registration` (required), `type` (required), `weglide_aircraft_id` (optional positive integer), and `weglide_aircraft_name` (written on export for readability, ignored on import).

**airfields.csv:** `name` (required), `latitude` and `longitude` (optional decimal degrees), `elevation_m` (optional integer), `match_radius_m` (optional integer) and `clubs` (optional, club names separated by semicolons).

**Behaviour**

- A glider or airfield row whose key already exists is skipped and reported, never updated. Airfields without coordinates are flagged incomplete.
- A flights import first resolves gliders and airfields, offering stubs and the "use existing…" choice for unmatched names, then asks about the launch type and log number defaults if needed, then commits everything in one transaction tagged with a batch ID.
- Errors are listed as file, row (the header is row 1), column, the value found and what was expected, for example `Row 14, date: found 18/10/26, expected YYYY-MM-DD`. Show up to 200 errors, then a count of the rest.

**Optional columns for the two Settings:** `launch_time` and `landing_time` (HH:MM, local 24-hour), `landing_airfield` (an airfield name, with unknown names handled like other unmatched airfields) and `landing_place` (free text). Setting both landing columns on one row is an error, and a landing time must be after the launch time. The duration column stays required.

**PIC column:** `pic` holds the name of the pilot in command. If the column is missing or some cells are blank, the import screen offers once to use the pilot profile name for the rows whose role is PIC, INSTRUCTOR or COACH. A blank cell on a STUDENT or MUTUAL row fails with its row number.

## Appendix D: IGC parsing parameters

The record layouts below come from my knowledge of the IGC format, because I could not read the specification text. Check them against Appendix A of the IGC technical specification before relying on them. The numeric defaults are proposals, kept as named constants in one place so they can be tuned against real files.

**Parsing**

- **Date:** the HFDTE header carries DDMMYY (a newer form is `HFDTEDATE:DDMMYY,NN`), with the two-digit year read as 20YY. This is the UTC date of the first fix.
- **Fixes:** B records give the UTC time as HHMMSS, latitude as DDMMmmmN or S, longitude as DDDMMmmmE or W (minutes in thousandths), a validity flag, and pressure and GNSS altitudes. Use only fixes marked valid (A) and use GNSS altitude. When a fix time is earlier than the one before, add a day.
- **Speed:** the haversine distance between consecutive valid fixes divided by the time between them. Pairs more than 60 seconds apart are skipped.

**Detecting a flight**

- **Start:** ground speed at or above 50 km/h held for 10 seconds. The segment starts at the first fix of that run.
- **End:** ground speed below 50 km/h held for 30 seconds. The segment ends at the first fix of that run.
- **Guards:** the segment must last at least 30 seconds and climb at least 50 m above its starting altitude at some point. The threshold is low because the pilot's logbook contains one-minute cable-break flights.
- **Known weakness:** in a strong headwind a glider's ground speed can drop below 50 km/h in flight. If real files show false endings, add a condition that the low speed coincides with being close to the ground.

**Results**

- **Duration:** landing time minus launch time, rounded to the nearest whole minute, with a minimum of one minute.
- **Flight date:** the launch instant converted to the device's timezone, taking its calendar date.
- **Preview:** launch and landing times in local time, as HH:MM, for checking, and stored in the flight only when the Times setting is on.
- **Several segments:** listed with local launch time and duration. Each is imported separately and takes the next log number in turn.

**Matching**

- **Airfield:** measure from the launch point, which is the first fix of the segment, to each airfield with coordinates. The default match radius is 1000 m, overridden by `match_radius_m` where set. Choose the nearest airfield within its radius and list others in range with their distances. With no match, offer to create one, pre-filled with the launch coordinates and the fix altitude as the elevation.
- **Glider:** compare the glider ID header (`HFGIDGLIDERID`) with the glider registration key. If there is no match, the pilot chooses. The glider type header is ignored.

**Landing place (when the Place setting is on):** compare the landing fix, the last fix of the segment, with the launch airfield. Inside its radius, leave the landing place blank. Inside another airfield's radius, preselect that airfield. Otherwise pre-fill the free-text landing place with the coordinates, for example -36.7842, 147.1700, for the pilot to rename. No create-airfield prompt is shown for landings.

**Tests:** keep a handful of the pilot's own IGC files in the repository with the date and duration written in the logbook for each, and assert that the parser agrees to within a minute.

## Appendix E: WeGlide client

All WeGlide code sits behind one interface with a fake implementation for tests. The base URL is a single constant, `https://api.weglide.org`, with `GET /v1/aircraft` and `POST /v1/igcfile`. The pilot supplied both paths, and the shared host is an assumption to check with a first real call.

- **Headers:** every request sends a `User-Agent` of the form `<app name>/<version> (Android)`. The app name is a project-setup decision.
- **Aircraft list:** GET with no credentials. The response is a JSON array whose entries have `id`, `name`, `kind`, `sc_class` and `dmst_index`. Skip entries without a numeric `id` and a string `name`. Cache the raw response in a file in app-private storage, load the cache first, refresh on request and automatically when it is older than 30 days, and keep the cache if a refresh fails. With no cache and no network, show a message.
- **Search:** lower-case both strings, remove accents, and remove everything except letters and digits. Rank exact matches first, then starts-with, then contains, ties by name. Show the name with its `kind` and `sc_class` exactly as returned.
- **Picking:** store the ID on the glider and replace its type with WeGlide's name, after showing the confirmation line described above.
- **Upload gate:** a glider needs a `weglide_aircraft_id`, and the pilot needs the WeGlide ID and date of birth in Settings. If any is missing, prompt for it instead of failing.
- **Request:** multipart form data with `file` (the IGC bytes, with a name ending in .igc), `user_id` (digits), `date_of_birth` (YYYY-MM-DD) and `aircraft_id` (integer as text).
- **Response:** HTTP 201 means success, as in XCSoar's source. Read the first element of the returned array and show its flight `id`, `scoring_date`, `registration`, `competition_id`, `user.name` and `aircraft.name`. Any other status is a failure: show the status code and the first 300 characters of the message, and do not retry automatically. A network error shows its message and offers a retry.
- **Timeouts:** connect 15 seconds, read and write 60 seconds.
- **Safety:** HTTPS only, never log the credentials, and never keep the IGC file.
- **Testing:** automated tests use a mock server. Real uploads are a manual test with the pilot's own account, run sparingly because WeGlide describes its API as not finalised.

## Appendix F: Project setup and tests

- **Identity:** the app name and package ID are not yet chosen. Ask the pilot before creating the project.
- **Android versions:** propose a minimum of API 26 (Android 8.0), which has the java.time classes built in, pending the decision listed below. Use the current stable compile and target versions at project start.
- **Stack:** Kotlin, Jetpack Compose with Material 3, Navigation Compose, Room with KSP and its Paging integration, DataStore (Preferences), WorkManager for scheduled exports, kotlinx.serialization for JSON, and OkHttp for HTTP. Use the latest stable releases at project start, pinned in a version catalogue, and check them against the official documentation. CSV needs only a small RFC 4180 reader and writer in the core package.
- **Layout:** one Gradle module with `core` (pure Kotlin, no `android.*` imports), `data` (Room, DataStore, files) and `ui` packages. A `testdata` folder holds the pilot's glider table, an anonymised flights CSV and a few real IGC files.
- **Permissions:** INTERNET only for the first releases. The LXNAV download, if it is built, adds the Bluetooth permissions described in its section. Files are read and written through the system document pickers, with a persisted tree permission for the backup folder, so no storage permissions are needed.
- **Scheduled backups:** after each saved change, if at least 20 changes or 7 days have passed since the last export, enqueue a one-time WorkManager job. Keep the last 10 files, named glider-logbook-YYYYMMDD-HHMMSS.json.
- **Display:** dates use the device locale's short format, durations use the Settings choice, the UI is English, Material dynamic colour is used, dark mode follows the system, and rotation must not lose form state. Touch targets are at least 48 dp.

**Tests**

- **Plain JVM:** duration arithmetic including overflow, CSV parsing and the error messages, a JSON export-import-export round trip that must be identical, the IGC parser against the sample files, and the WeGlide search normalisation and ranking.
- **On a device or emulator:** the Room constraints (unique `log_no` that ignores tombstones, foreign keys, partial indexes), and migrations as soon as a second schema version exists.
- **UI:** a smoke test that creates a flight, filters the list, and checks the totals.

## Later features

OGN import and an iOS port are not planned for the first releases, but the design leaves room for both.

- **OGN import:** Flightbook exposes a JSON API (its repository holds the documentation), but that documentation could not be read and its usage terms are unverified. KTrax offers a logbook API only to subscribers with an API key, limited to one automated query a minute. OGN identifies aircraft by device, so gliders would need an optional device ID. With no times stored, matching an OGN flight to a manual entry would rely on glider, date and duration, and same-length flights on one day would be left for the pilot to decide.
- **iOS:** the versioned JSON schema and multiplatform-capable libraries (Room, Compose) are the only preparation, and neither costs anything now.

## Build order

The first three steps give a usable logbook with safe backups before any integration is built.

1. **Schema and the flight form.** Done when the database creates with every constraint in Appendix A, airfields, gliders, clubs and launch types can be created and edited, a flight can be entered with every rule enforced (unique log number, mandatory launch type, the club rule, the pilot in command rules, H:MM duration entry with overflow), and the Room constraint tests pass.
2. **Flights list, filters, opening balances and totals.** Done when 10,000 generated flights scroll without noticeable delay, every filter works alone and combined, totals match a hand calculation with and without a filter, and the Settings choices for hours display, PIC counting, landing place and times work.
3. **JSON backup, restore and scheduled copies.** Done when an export, import and export round trip is identical, Restore into an empty database reproduces the original, Merge reports a log number collision, scheduled exports write timestamped files to the chosen folder, and first-run restore works.
4. **CSV export and import, including stubs and undo.** Done when the exports re-import cleanly, the pilot's prepared flights CSV and 14-glider table import, errors name their rows, and Undo last import removes the batch and its unused stubs.
5. **Aircraft logbook calculator.** Done when a day's flights sum correctly, unticking a flight updates hours and flights, extra manual entries work, results match a hand calculation including overflow, the stored logbook total prefills and can be overwritten, Save new total writes the result and the flying day back, and the double-add warning appears.
6. **IGC import.** Done when the sample files give the logbook's date and duration to within a minute, airfield matching and the create prompt work, multi-segment files list their segments, the form is pre-filled, and an IGC file shared from another app opens the flow.
7. **WeGlide upload.** Done when the aircraft list downloads, caches and searches, picking an entry sets the ID and type, mock-server tests cover success and failure, and one manual upload with the pilot's own account succeeds.
8. **OGN import.** Not to be started until it has its own specification. The LXNAV flight download is likewise not a build step until its prototype, described in its own section, has worked.

## Decisions and open points

Four of these points were settled during review, and one remains open.

1. **Totals:** Instructor and Coach flights - selectable in Settings if they also count toward PIC, or stay separate with Dual as their sum. The Totals screen shows the five buckets and Combined.
2. **Landing place:** Optional, behind the Record landing place Setting, off by default. Launch and landing times are likewise optional, behind a separate Record launch and landing times Setting, off by default.
3. **Aircraft logbook rounding:** whether the logbook wants H:MM or decimal hours, and any rounding rule. Selectable in settings, rounding to nearest minute.
4. **Launch type list:** the seed values = Aero, Winch, Self, Auto
5. **Minimum Android version:** not yet chosen.

## Project note for Claude Code

Paste this into `CLAUDE.md` at the root of the repository.

- This repository builds an Android glider flight logbook. The specification and its appendices are the source of truth. Read them fully before writing code.
- Work one build-order step at a time. At the end of each step, list what was built, what was tested, and any deviation from the specification, then wait for the pilot's go-ahead before starting the next step.
- Do not build anything listed as out of scope, and do not add fields, screens or settings that the specification does not mention. Ask first. The LXNAV flight download is an investigation: build only its prototype, and report the findings before anything else.
- The appendices hold proposed defaults. Follow them, but ask before changing a default, and ask for any value the specification leaves open: the app name, the package ID and the minimum Android version.
- Check library versions, Android APIs and the IGC record layouts against official documentation instead of relying on memory. Prefer stable releases.
- Keep the `core` package free of `android.*` imports, and keep all WeGlide code behind one interface with a fake implementation.
- Write the tests listed in Appendix F as part of each step, and run them before reporting a step as done.
- Never log, export or commit the WeGlide ID, the date of birth or any other credential. Never keep an IGC file after an import or upload.
- Do not make real WeGlide requests from automated tests. A manual upload with the pilot's own account happens only when the pilot asks for it.
- Use the files in `testdata` as the test fixtures. If a fixture is missing, ask the pilot rather than inventing data.

## Sources consulted

Research ran on 2026-10-18; several pages were read as search extracts, not in full.

- [Add Times (Akaflieg Freiburg)](https://github.com/Akaflieg-Freiburg/addhoursandminutes)
- [KTrax API usage](https://ktrax.kisstech.ch/api-usage)
- [WeGlide developer docs](https://docs.weglide.org/creators/developers.html)
- [WeGlide flight upload](https://docs.weglide.org/how_to/flight_upload.html)
- [WeGlide: getting started](https://magazine.weglide.org/getting-started/)
- [WeGlide Tasks in XCSoar](https://magazine.weglide.org/weglide-tasks-xcsoar/)
- [XCSoar 7.23 release notes](https://xcsoar.org/news/2022/02/11/xcsoar-7-dot-23-released.html)
- [Room release notes](https://developer.android.com/jetpack/androidx/releases/room)
- [Compose Multiplatform and Jetpack Compose](https://jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-and-jetpack-compose.html)
- [Android SQLite performance best practices](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Auto Backup for Apps](https://developer.android.com/identity/data/autobackup?authuser=2)
- [aerofiles IGC header examples](https://aerofiles.readthedocs.io/en/latest/guide/igc-writing.html)

* [LXNAV Data Port Specification 1.04 (Draft, Internal)](https://github.com/XCSoar/XCSoar/files/11738481/LXNAV_DataPort_Specification1.04.pdf)
* [XCSoar 7.44 release notes](https://xcsoar.org/news/2026/03/22/xcsoar-7-dot-44-released.html)
* [XCSoar documentation index](https://xcsoar.org/docs/)
* [Android Bluetooth permissions](https://developer.android.com/guide/topics/connectivity/bluetooth/permissions)
