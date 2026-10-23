package io.github.jgfyyvzss.gliderpilotlog.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jgfyyvzss.gliderpilotlog.SoarlogsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Writes one automatic backup to the pilot's folder. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SoarlogsApp
        if (!app.backup.isAutoBackupDue()) return Result.success()
        return when (app.backupFolder.backUpNow()) {
            is BackupOutcome.Written, BackupOutcome.NoFolder -> Result.success()
            is BackupOutcome.Failed -> Result.failure()
        }
    }
}

/**
 * Watches the database and, a few seconds after changes settle, enqueues one backup job when the schedule says
 * one is due. Also checks once when started, for the days trigger.
 */
class BackupScheduler(private val context: Context, private val db: LogbookDatabase, private val backup: BackupService) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            db.invalidationTracker
                .createFlow("flight", "glider", "airfield", "airfield_club", "club", "launch_type", "opening_balance", "witness_signoff")
                .debounce(5_000)
                .collect {
                    if (backup.isAutoBackupDue()) {
                        WorkManager.getInstance(context).enqueueUniqueWork(
                            "auto-backup",
                            ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<BackupWorker>().build(),
                        )
                    }
                }
        }
    }
}
