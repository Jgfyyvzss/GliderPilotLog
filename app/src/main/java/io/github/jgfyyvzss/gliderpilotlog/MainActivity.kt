package io.github.jgfyyvzss.gliderpilotlog

import android.content.Intent
import android.net.Uri
import android.os.Build
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
        (application as SoarlogsApp).startBackupScheduler()
        // A recreated activity has already handled the intent it was started with.
        if (savedInstanceState == null) openIgc(intent)
        setContent {
            SoarlogsTheme { SoarlogsRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openIgc(intent)
    }

    /** An IGC file opened or shared from another app goes to the same import flow as the file picker. */
    private fun openIgc(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        uri?.let((application as SoarlogsApp).igcImport::open)
    }
}
