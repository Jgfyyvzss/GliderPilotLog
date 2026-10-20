package io.github.jgfyyvzss.gliderpilotlog.core.backup

import io.github.jgfyyvzss.gliderpilotlog.core.Keys

/** A small valid backup shared by the backup tests. */
object BackupTestData {
    const val T1 = "2026-10-18T09:47:00Z"
    const val T2 = "2026-10-19T10:00:00.123Z"
    val winch = Keys.seededLaunchTypeId("Winch")

    fun file() = BackupFile(
        exportedAt = T2,
        appVersion = "0.1.0",
        launchTypes = listOf(
            BackupLaunchType(winch, "Winch", "winch", 1, false, "1970-01-01T00:00:00Z"),
            BackupLaunchType(Keys.seededLaunchTypeId("Aero"), "Aero", "aero", 0, false, "1970-01-01T00:00:00Z"),
        ),
        clubs = listOf(BackupClub("c1", "MBGC", "mbgc", T1)),
        airfields = listOf(
            BackupAirfield(
                id = "a1", name = "YMBT", nameKey = "ymbt", clubIds = listOf("c1"), latitude = -36.7314, longitude = 147.17,
                elevationM = 300, defaultClubId = "c1", defaultLaunchTypeId = winch, updatedAt = T1,
            ),
        ),
        gliders = listOf(BackupGlider("g1", "FQM", "fqm", "Hornet", updatedAt = T1)),
        flights = listOf(
            flight("f2", 341, T2),
            flight("f1", 340, T1),
            flight("f0", 340, T1, deletedAt = T2), // a tombstone may share a live log number
        ),
        openingBalances = listOf("PIC", "STUDENT", "INSTRUCTOR", "COACH", "MUTUAL").map { BackupOpeningBalance(it, updatedAt = T1) },
        settings = BackupSettings(pilotName = "Pat Pilot", pilotIdentifiers = listOf(BackupIdentifier("ARN", "123456"))),
    )

    fun flight(id: String, logNo: Int, updatedAt: String, deletedAt: String? = null, notes: String? = null) = BackupFlight(
        id = id, logNo = logNo, date = "2026-10-17", durationMinutes = 271, gliderId = "g1", airfieldId = "a1", clubId = "c1",
        launchTypeId = winch, role = "PIC", picName = "Pat Pilot", notes = notes, source = "MANUAL",
        updatedAt = updatedAt, deletedAt = deletedAt,
    )
}
