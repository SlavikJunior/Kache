package io.github.slavikjunior.kache.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Hosts the Compose report screen.
 *
 * [enableEdgeToEdge] is required on API 35+: without it the content view is laid out
 * edge to edge but the system bars are not made transparent, so content ends up hidden
 * behind them. `Scaffold` applies the insets that `KacheSampleTheme` does not consume.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KacheSampleTheme {
                SampleScreen()
            }
        }
    }
}