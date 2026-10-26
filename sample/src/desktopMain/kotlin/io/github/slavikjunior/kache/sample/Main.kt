package io.github.slavikjunior.kache.sample

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.slavikjunior.kache.sample.di.createAppContainer
import io.github.slavikjunior.kache.sample.ui.cars.CarsViewModel

/**
 * Desktop entry point.
 *
 * The ViewModel is remembered for the lifetime of the window: a desktop window is not
 * recreated on resize, so there is nothing for a `ViewModelStore` to survive. The container
 * is created once for the same reason, and holds the file-backed caches.
 */
fun main() = application {
    val container = remember { createAppContainer() }
    val viewModel = remember { CarsViewModel(container.carRepository, container.cacheLog) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Kache sample · car catalogue",
        state = rememberWindowState(width = 480.dp, height = 900.dp),
    ) {
        SampleApp(viewModel = viewModel)
    }
}
