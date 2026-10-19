package io.github.jgfyyvzss.gliderpilotlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jgfyyvzss.gliderpilotlog.ui.SoarlogsRoot
import io.github.jgfyyvzss.gliderpilotlog.ui.theme.SoarlogsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SoarlogsTheme { SoarlogsRoot() }
        }
    }
}
