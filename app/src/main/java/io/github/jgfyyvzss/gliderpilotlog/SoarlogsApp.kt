package io.github.jgfyyvzss.gliderpilotlog

import android.app.Application
import androidx.annotation.VisibleForTesting
import io.github.jgfyyvzss.gliderpilotlog.data.BackupFolder
import io.github.jgfyyvzss.gliderpilotlog.data.BackupScheduler
import io.github.jgfyyvzss.gliderpilotlog.data.BackupService
import io.github.jgfyyvzss.gliderpilotlog.data.CsvService
import io.github.jgfyyvzss.gliderpilotlog.data.FilterStore
import io.github.jgfyyvzss.gliderpilotlog.data.IgcImportStore
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookDatabase
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.SettingsRepository
import io.github.jgfyyvzss.gliderpilotlog.data.TotalsService
import io.github.jgfyyvzss.gliderpilotlog.core.lxnav.FilePartialDownload
import io.github.jgfyyvzss.gliderpilotlog.data.lxnav.BluetoothLoggers
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.AircraftCatalog
import io.github.jgfyyvzss.gliderpilotlog.data.weglide.OkHttpWeGlideApi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SoarlogsApp : Application() {
    val database by lazy { testDatabase ?: LogbookDatabase.build(this) }
    val repository by lazy { LogbookRepository(database) }
    val settings by lazy { SettingsRepository(this) }
    val filter = FilterStore()
    val totals by lazy { TotalsService(repository, settings) }
    val backup by lazy { BackupService(database, settings, appVersion()) }
    val backupFolder by lazy { BackupFolder(this, backup, settings) }
    val aircraft by lazy {
        AircraftCatalog(
            savedFile = File(filesDir, "weglide-aircraft.json"),
            bundled = { assets.open("weglide-aircraft.json").use { it.readBytes().decodeToString() } },
            api = OkHttpWeGlideApi(OkHttpWeGlideApi.userAgent(appVersion())),
        )
    }
    val csv by lazy { CsvService(database, aircraftName = { id -> aircraft.find(id)?.name }) }
    val igcImport by lazy { IgcImportStore(this, appScope) }
    val loggers by lazy { BluetoothLoggers(this) }
    /** The one unfinished logger download, kept out of Android's Auto Backup. */
    val loggerPartial by lazy { FilePartialDownload(File(noBackupFilesDir, "lxnav.part")) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var schedulerStarted = false

    /**
     * Starts watching for changes that make an automatic backup due. Called from the first screen rather than
     * onCreate, so UI tests can supply their database first.
     */
    fun startBackupScheduler() {
        if (schedulerStarted || testDatabase != null) return
        schedulerStarted = true
        BackupScheduler(this, database, backup).start(appScope)
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    companion object {
        /** Set by UI tests before the first screen opens, so they use an in-memory database. */
        @VisibleForTesting
        var testDatabase: LogbookDatabase? = null
    }
}
