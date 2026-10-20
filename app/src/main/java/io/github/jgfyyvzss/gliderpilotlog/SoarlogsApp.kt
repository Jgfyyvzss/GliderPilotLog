package io.github.jgfyyvzss.gliderpilotlog

import android.app.Application
import androidx.annotation.VisibleForTesting
import io.github.jgfyyvzss.gliderpilotlog.data.FilterStore
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookDatabase
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.SettingsRepository
import io.github.jgfyyvzss.gliderpilotlog.data.TotalsService

class SoarlogsApp : Application() {
    val database by lazy { testDatabase ?: LogbookDatabase.build(this) }
    val repository by lazy { LogbookRepository(database) }
    val settings by lazy { SettingsRepository(this) }
    val filter = FilterStore()
    val totals by lazy { TotalsService(repository, settings) }

    companion object {
        /** Set by UI tests before the first screen opens, so they use an in-memory database. */
        @VisibleForTesting
        var testDatabase: LogbookDatabase? = null
    }
}
