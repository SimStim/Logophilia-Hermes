package eu.logophilia.hermes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.logophilia.hermes.ui.HermesApp
import eu.logophilia.hermes.ui.theme.HermesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesTheme {
                HermesApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // §4 trigger: application opened or returned to the foreground.
        (application as HermesApplication).syncCoordinator.onForeground()
    }
}
