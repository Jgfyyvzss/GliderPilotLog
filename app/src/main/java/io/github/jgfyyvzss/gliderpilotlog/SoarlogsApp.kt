package io.github.jgfyyvzss.gliderpilotlog

import android.app.Application
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookDatabase
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import io.github.jgfyyvzss.gliderpilotlog.data.SettingsRepository

class SoarlogsApp : Application() {
    val database by lazy { LogbookDatabase.build(this) }
    val repository by lazy { LogbookRepository(database) }
    val settings by lazy { SettingsRepository(this) }
}
