package world.xhere.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.koin.android.ext.android.inject
import world.xhere.app.detail.IncomingLinks
import world.xhere.app.ui.theme.XHereTheme

class MainActivity : ComponentActivity() {
    private val links: IncomingLinks by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) offer(intent)
        setContent {
            XHereTheme {
                XHereApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offer(intent)
    }

    private fun offer(intent: Intent?) {
        links.offer(intent?.dataString)
    }
}
