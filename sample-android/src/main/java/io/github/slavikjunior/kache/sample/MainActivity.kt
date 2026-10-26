package io.github.slavikjunior.kache.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Hosts the shared Compose UI.
 *
 * [enableEdgeToEdge] is required on API 35+: without it the content view is laid out edge
 * to edge but the system bars are not made transparent, so content ends up hidden behind
 * them. `Scaffold` in the shared screen applies the insets that the theme does not consume.
 *
 * The container is owned by [SampleApplication], not by this activity: the caches must
 * survive a configuration change, and the HTTP client should not be rebuilt per rotation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SampleApplication).container

        setContent {
            SampleAppAndroid(container = container)
        }
    }
}
