# Optional feature: Witness sign-off for the glider logbook

Oct 20, 2026 · @Peter

## Status and purpose

This optional witness sign-off lets a pilot show that someone else confirmed a logbook entry, and it is not part of the main specification's build order. It aims for reasonable assurance that an entry was not self-witnessed, not for any legal standard.

The design suits an instructor who may sign only once, so there is no account, no PIN and nothing to remember. The witness types a name and number, is photographed by the phone's camera, and ticks a consent line. The app then seals the flight's signed fields together with those details, so a later edit to a signed field shows.

## How it works

The feature sits behind a Setting called Witness sign-off, which is off by default. Turning it off hides every witness screen and keeps any existing data.

1. The pilot opens a flight, or a day's group in the Flights list, and taps Witness. Each unwitnessed flight in the group is ticked and can be unticked.
2. The witness enters their name and licence or membership number, or taps a previous witness from a quick-pick list built from earlier sign-offs.
3. The phone's own camera app opens and the witness takes a photo of their face.
4. The witness ticks the consent line: "I witnessed these flights and agree my photo is stored with them in this logbook."
5. The app saves one sign-off and seals each ticked flight.

**Photo handling**

- The photo is taken through the system camera app, so the app does not declare the CAMERA permission and shows no permission prompt.
- The app scales the photo to 200 pixels on its longest side and re-encodes it as a JPEG at quality 70, which removes embedded location data. The result is about 10 to 20 KB, and the original is deleted at once.

**Checks**

- A witness whose name or number matches the pilot profile is rejected.
- On Student flights, the witness name is pre-filled from the PIC field when all ticked flights share it, with a warning if the witness is someone else.
- Name and number are both required.

## The seal

Each witnessed flight carries a SHA-256 seal that lets the app tell whether a signed field has changed since the witnessing.

**Signed fields.** The hash input is UTF-8 text with one `key=value` line per field, in this fixed order and empty for a null: `seal_version`, `log_no`, `date`, `duration_minutes`, `glider_id`, `airfield_id`, `launch_type_id`, `role`, `pic_name`, `launch_time`, `landing_time`, `landing_airfield_id`, `landing_place`, then `witness_name`, `witness_identifier`, `signed_at` (ISO 8601, UTC) and `photo_sha256` (empty when no photo was taken). Notes, club, source, import batch and WeGlide data are not signed.

- **IDs:** the glider, airfield and launch type are signed by ID, so renaming a glider or airfield does not break a seal, while pointing the flight at a different one does.
- **Photo:** the seal covers the photo's hash, not its bytes, so removing the photo later keeps the seal valid.
- **Checking:** the seal is recomputed whenever a flight is shown and whenever a file is imported, and compared with the stored value. A mismatch shows "Changed since witnessed". That status is never stored.
- **Versions:** `seal_version` fixes the field list. A flight with a version the app does not know shows "cannot verify".
- **No secret:** the seal detects edits but cannot stop someone who recomputes it.

## Data model

One new table holds the sign-off events, and two nullable columns on the flight point to them. Types follow Appendix A of the main specification.

| Record | Fields | Rules |
| --- | --- | --- |
| witness\_signoff (new table) | id (UUID), witness\_name, witness\_identifier, signed\_at (epoch ms, UTC), photo (JPEG bytes, nullable), photo\_sha256 (nullable), photo\_removed, seal\_version, consent\_confirmed, updated\_at, deleted\_at | Name and identifier are required and non-blank. `consent_confirmed` must be true. Name and identifier are never edited after signing, so a correction means deleting the sign-off and witnessing again. The photo is at most 100 KB. |
| flight (two added columns) | witness\_signoff\_id (nullable foreign key), witness\_seal (text, nullable) | Both are set together or both are null. Index on `witness_signoff_id`. |
| Setting | witness\_signoff (boolean) | Default off, and backed up with the other settings. |

- **Batches:** one sign-off can cover many flights, such as a day's flying. The seal is per flight.
- **Quick-pick list:** it is the distinct name and identifier pairs of live sign-offs, so there is no separate witness table.
- **Storage:** the photo sits in the sign-off row as a BLOB. At 10 to 20 KB per sign-off, no separate file storage is needed.

## Display, editing and removal

- **Flights list:** a small Witnessed mark on sealed flights and a Changed mark where the seal no longer matches. A Witness filter offers any, witnessed, not witnessed and changed since witnessed.
- **Flight detail:** the witness name and identifier, the signing date (with "witnessed N days after the flight" when later than the flight), the photo thumbnail, and the seal status.
- **Editing:** changing a signed field of a witnessed flight first asks "This flight was witnessed by X. Changing it will mark it as changed since witnessed." Notes and club can be edited freely. The pilot can witness the flight again afterwards, which creates a new sign-off.
- **Remove photo:** an action on a sign-off that deletes the photo bytes and keeps the name, the number and the seals valid.
- **Remove witness mark:** the pilot can detach the witness from a flight after a confirmation. A sign-off that no live flight refers to is deleted with its photo.
- **Deleting a flight:** it follows the normal rules, including the same clean-up of unreferenced sign-offs.
- **Setting off:** the witness screens are hidden, nothing is deleted, and seals are not recomputed until the Setting is turned on again.

## JSON export and import

Witness data travels in the existing JSON backup as one new array plus two fields on each witnessed flight.

**Export**

- **`witness_signoffs`:** one object per sign-off with `id`, `witness_name`, `witness_identifier`, `signed_at`, `photo` (base64 JPEG, or null), `photo_sha256`, `photo_removed`, `seal_version`, `consent_confirmed`, `updated_at` and `deleted_at`. The array is sorted by `id` like the others.
- **Flights:** each flight object gains `witness_signoff_id` and `witness_seal`, null when the flight is not witnessed. The `settings` object gains `witness_signoff`.
- **Round trip:** the stored JPEG bytes are written unchanged with stable base64, so export, import and export still produce identical files.
- **Size:** a 200 pixel JPEG of 10 to 20 KB becomes about 14 to 27 KB in base64. Sign-offs are per batch, so hundreds add only a few MB.
- **Photo choice:** an Include witness photos option at export, on by default for backups to the pilot's own folder. Without photos the names, numbers, hashes and seals remain and the photo field is null. The manifest records `contains_photos`, so a file carrying faces is recognisable.

**Import**

- **Photo checks:** the photo must be valid base64, a JPEG, at most 100 KB, and must match its `photo_sha256`. A bad photo is dropped and reported, and the sign-off stays.
- **Seals:** every seal is recomputed and the summary reports, for example, "12 witnessed flights verified, 0 changed". A mismatch never blocks the import, and the flight simply shows Changed since witnessed.
- **Merge:** sign-offs match by `id` and the newer `updated_at` wins. If the signed fields of a flight differ between the two sides, the newer flight wins and its seal shows honestly whether it still matches.
- **Dangling reference:** a flight pointing at a sign-off that is not in the file keeps its data, loses the two witness fields, and is reported.
- **Unknown versions:** a flight with an unknown `seal_version` keeps its seal and shows "cannot verify".
- **Format version:** the new fields are optional and unknown fields are ignored. If the main specification has not shipped, they join format version 1. If it has, bump the version, because an older app would drop witness data on a round trip.

## CSV, WeGlide and Auto Backup

- **Flights CSV:** the export can include read-only `witnessed_by` and `witnessed_on` columns. Import ignores them and never creates a witness mark, so a spreadsheet cannot be used to self-witness, and every imported flight starts unwitnessed.
- **Glider and airfield CSV:** unchanged.
- **WeGlide and IGC import:** unchanged. Witness details and photos are never sent anywhere.
- **Android Auto Backup:** the main specification leaves Auto Backup at the Android default, which copies the app's database to the user's Google account backup. Photos stored in the database would be copied with it. Keeping them out means storing the photos as files in a directory that the backup rules exclude. This is listed as an open question below.

## Privacy and consent

A face photo identifies the witness, so the feature is opt-in and designed to keep the photo small, local and removable. Privacy rules differ by country and by whether the app is used personally or by a club, so check the rules where you live. This is not legal advice.

- **Consent:** the consent line must be ticked for every sign-off, and the confirmation is saved with it. The screen should show the witness exactly what is stored and why before the camera opens.
- **Local only:** the app never uploads the photo. It lives in the database, in the pilot's own JSON backups, and possibly in Android's Auto Backup.
- **Minimal data:** 200 pixels on the longest side, re-encoded so that location data is dropped.
- **Removal:** the pilot can delete a photo at any time, for example at the witness's request, keeping the name, the number and the seals.
- **Sharing:** the export choice allows a file without photos, and the manifest marks files that contain them.

## What it does not give you

The feature records that someone was photographed at the phone and gave a name, a number and a time, and it detects casual edits afterwards. It does not prove who the witness was.

- **No identification:** the app does not check that the face, name and number belong together, or to a real instructor.
- **Staged witnesses:** a pilot can photograph a friend and type any name. A reviewer who knows the pilot may notice a photo of the pilot instead.
- **No secret in the seal:** someone who edits a JSON file and recomputes the hashes can forge a witnessed flight. In the app, an edited flight is flagged, but the pilot can witness it again.
- **No trusted clock:** the signing time comes from the phone, which the pilot controls.
- **No legal standard:** none is claimed or needed. MyFlightbook's own documentation makes the same point about its signatures, saying a made-up instructor account is no different from signing your own logbook.

## If adopted: changes and build step

Adopting the feature touches seven parts of the main specification and adds one build step.

- **Data model and Appendix A:** the `witness_signoff` table, the two flight columns and the Setting.
- **Appendix B:** the `witness_signoffs` array, the flight fields, the Setting, `contains_photos` and the Include witness photos choice.
- **Appendix C:** read-only `witnessed_by` and `witnessed_on` columns in the flights export, ignored on import.
- **Screens:** the Witness action, the marks and filter in the Flights list, the detail view and the Setting.
- **Appendix D and F:** no new permission, because the photo comes from the system camera app, plus the seal tests below.
- **Decisions:** the privacy points above.
- **Build order:** one new step, after the JSON backup and CSV steps, because it extends both.

**Build step: witness sign-off.** Done when:

1. The Setting shows and hides every witness screen without deleting data.
2. A batch sign-off creates one sign-off and seals each ticked flight.
3. The stored photo is at most 25 KB and carries no location data.
4. A seal verifies, and it flags an edit to a signed field but not to notes or club. Tests use fixed test vectors for the canonical text.
5. A witness matching the pilot's profile name or number is rejected, and Student flights pre-fill the witness from the PIC field.
6. A JSON export, import and export round trip is identical, including photos, and a photo-free export keeps names, hashes and seals.
7. Import drops a bad photo, reports the seal summary, and never blocks on a mismatch.
8. CSV import never creates a witness mark.
9. Remove photo and Remove witness mark work, and unreferenced sign-offs are cleaned up.

## Open questions

1. **Which flights:** can any flight be witnessed, as assumed here, or only Student ones? A reminder for unwitnessed Student flights is a possible addition.
2. **Witness number:** it is required here. Is that too strict for a one-off witness who has no number to hand?
3. **Auto Backup:** accept the photos in Android's Google backup, or store them as files in a directory that the backup rules exclude?
4. **Consent wording:** the line proposed in How it works needs the pilot's approval and a check against local privacy rules.
5. **Photo size:** 200 pixels and quality 70 are proposals. Check on a real phone that the face is still recognisable.
6. **Drawn signature:** a signature on the same screen would add familiarity but no assurance. Add it or leave it out?

## Sources

Pages consulted on 2026-10-20; several were read as search extracts, not in full.

- [MyFlightbook: instructor signatures](https://myflightbook.com/logbook/mvc/pub/CFISigs)
- [ForeFlight: endorsing a logbook](https://support.foreflight.com/hc/en-us/articles/216184638)
- [ForeFlight: signatures lock entries](https://blog.foreflight.com/2016/06/03/6-reasons-to-go-digital-with-foreflights-integrated-logbook/)
- [LogTen: signing entries](https://intercom.help/pilotbase/en/articles/11625726-getting-my-logbook-and-endorsements-signed-in-logten)
- [Android: minimize permission requests](https://developer.android.com/privacy-and-security/minimize-permission-requests)
