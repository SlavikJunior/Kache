package io.github.slavikjunior.kache.sample

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.slavikjunior.kache.sample.di.AppContainer
import io.github.slavikjunior.kache.sample.ui.cars.CarsViewModel

/**
 * Android entry into the shared UI.
 *
 * The ViewModel comes from the activity's store, so it survives a rotation while the
 * composables are recreated; desktop and iOS do not need this indirection and construct the
 * ViewModel directly.
 */
@Composable
fun SampleAppAndroid(container: AppContainer) {
    val viewModel: CarsViewModel = viewModel(factory = CarsViewModel.factory(container))
    SampleApp(viewModel = viewModel)
}
