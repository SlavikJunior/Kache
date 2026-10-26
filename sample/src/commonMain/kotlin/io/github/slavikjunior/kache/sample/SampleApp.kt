package io.github.slavikjunior.kache.sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.slavikjunior.kache.sample.ui.cars.CarsRoute
import io.github.slavikjunior.kache.sample.ui.cars.CarsViewModel
import io.github.slavikjunior.kache.sample.ui.theme.KacheSampleTheme

/**
 * The whole app, shared by every platform.
 *
 * The ViewModel is a parameter rather than something created here: Android has to obtain it
 * from a `ViewModelStore` that survives configuration changes, while desktop and iOS have a
 * single window whose lifetime is the process. Owning that difference in the platform entry
 * point keeps this composable free of lifecycle assumptions.
 */
@Composable
fun SampleApp(viewModel: CarsViewModel, modifier: Modifier = Modifier) {
    KacheSampleTheme {
        CarsRoute(viewModel = viewModel, modifier = modifier)
    }
}
