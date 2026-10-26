package io.github.slavikjunior.kache.sample

import android.app.Application
import io.github.slavikjunior.kache.sample.di.AppContainer
import io.github.slavikjunior.kache.sample.di.createAppContainer

/**
 * Owns the composition root.
 *
 * The container lives on the process, not on the activity: the caches must survive a
 * configuration change, and the HTTP client should not be rebuilt for every rotation.
 */
class SampleApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createAppContainer(this)
    }
}
