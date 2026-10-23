# WeGlide upload: plan for the next round

Written 2026-10-23, for when work returns to the `weglide-upload` branch. Built on that branch the same day, with the decisions below; not yet tested on a phone.

## What the pilot wants

A file used to create a log entry, from the file picker, a share or the logger, is handed straight to the WeGlide upload. The upload screen then shows the file name and basic details (date, glider, duration) and is ready to upload. Choosing a file or downloading from the logger on the upload screen stays available, but is optional to that workflow.

## The plan

1. **Hand-off:** when the IGC import opens a file, it also passes that file (name and bytes) to the upload, using the bytes already read for the import.
2. **The saved flight's glider:** when a flight pre-filled from that file is saved, its glider is recorded with the file. The upload uses that glider's WeGlide type first, then the file's header match, then asks.
3. **The upload screen with a file waiting:** it shows the file name, date, launch time, duration and glider, the WeGlide aircraft and the Upload button, ready to go when credentials are saved. Choose file and From logger move below as "Use a different file". With no file waiting, the screen is as now.
4. **Keeping the file (corrected by the pilot):** "never keep an IGC file" was meant as "the logbook is not an archive of IGC files", not "never write one to storage". Holding a file only in memory is too fragile: after a 20-minute logger download, a crash, a flat battery, closing the app or a slip of the finger would lose it. So the file is saved to app-private storage, kept out of Auto Backup, and held until it is uploaded or expires. That allows downloading at the airfield and uploading later on Wi-Fi.
   - Options to decide: keep each file for a day or two, or keep the last N files. A count is awkward for instructors with many flights a day, so an age limit, perhaps with a generous count as a backstop, looks better.
   - Every saved file is deleted at expiry or when the pilot removes it. An uploaded file stays until then, so its flights can still be logged.
   - The upload screen could list the files waiting to upload, newest first, each with its details and an Upload button.
   - CLAUDE.md and the spec's IGC import, LXNAV and WeGlide sections were reworded to match (2026-10-23).
5. **Already uploaded:** after a successful upload the file is marked uploaded and is not offered for upload again; it stays until it expires, for logging.
6. **Tests:** the aircraft choice (saved flight's glider, then header match, then none) as a small `core` function with a JVM test, plus tests for the waiting-file store: expiry, removal after upload, and surviving a restart. The mock-server upload tests stay.
7. **Branches:** after main is committed, bring main into `weglide-upload` first. Expect conflicts in Home, navigation, the logger screen, Settings and WeGlideScreens, which both branches changed.

## Decisions

- Files are kept 7 days, at most 50, oldest deleted first.
- Both the logbook import and the upload screen show both statuses (logbook and WeGlide) with a button for the other job, so a pilot who wants both is not left with one done. Pilots who only log, or only upload, can ignore the other line.
