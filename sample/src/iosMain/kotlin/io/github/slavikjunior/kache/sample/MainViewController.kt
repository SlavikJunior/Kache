package io.github.slavikjunior.kache.sample

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import io.github.slavikjunior.kache.sample.di.createAppContainer
import io.github.slavikjunior.kache.sample.ui.cars.CarsViewModel
import platform.UIKit.UIViewController

/**
 * iOS entry point, called from the SwiftUI host in `iosApp`.
 *
 * One view controller for the whole app, so the ViewModel lives as long as the window does.
 * The container builds Room-backed caches; that is the only platform-specific part of the
 * cache setup.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val container = remember { createAppContainer() }
    val viewModel = remember { CarsViewModel(container.carRepository, container.cacheLog) }
    SampleApp(viewModel = viewModel)
}
