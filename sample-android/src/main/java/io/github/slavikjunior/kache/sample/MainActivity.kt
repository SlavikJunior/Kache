package io.github.slavikjunior.kache.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.slavikjunior.kache.sample.ui.cars.CarsRoute
import io.github.slavikjunior.kache.sample.ui.cars.CarsViewModel
import io.github.slavikjunior.kache.sample.ui.theme.KacheSampleTheme

/**
 * Hosts the Compose screen graph.
 *
 * [enableEdgeToEdge] is required on API 35+: without it the content view is laid out
 * edge to edge but the system bars are not made transparent, so content ends up hidden
 * behind them. `Scaffold` applies the insets that [KacheSampleTheme] does not consume.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SampleApplication).container

        setContent {
            KacheSampleTheme {
                val carsViewModel: CarsViewModel = viewModel(
                    factory = CarsViewModel.factory(container),
                )
                CarsRoute(viewModel = carsViewModel)
            }
        }
    }
}
