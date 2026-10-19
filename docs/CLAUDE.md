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

