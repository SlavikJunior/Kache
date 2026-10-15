package io.github.slavikjunior.kache.consumer.android

import android.app.Application
import android.content.Context
import io.github.slavikjunior.kache.android.KacheableViewModel
import io.github.slavikjunior.kache.android.l2Cache
import io.github.slavikjunior.kache.core.KmpCache
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import io.github.slavikjunior.kache.store.room.createFromContext
import kotlinx.serialization.Serializable

/**
 * Compile-time consumer of every published Android artifact.
 *
 * Nothing here runs. The module exists so that `assembleDebug` has to resolve the AARs from
 * the local Maven repository and compile against their public API, which is the step a
 * `project(...)` dependency would skip.
 */

@Serializable
internal data class Profile(val id: String, val name: String)

/** `cache-android` + `cache-storage`: the `Context`-based factory and its file engine. */
internal fun fileBackedCache(context: Context) =
    l2Cache(context, KotlinxJsonSerializer(Profile.serializer()))

/** `cache-store-room`: the Android Room factory, fed through the engine overload. */
internal fun roomBackedCache(context: Context) =
    l2Cache(
        storageEngine = createFromContext(context, "consumer.db"),
        serializer = KotlinxJsonSerializer(Profile.serializer()),
        maxSize = 100,
    )

/** `cache-android`: the caching ViewModel, which needs `KacheableViewModel` as `api`. */
internal class ProfilesViewModel(
    application: Application,
    cache: KmpCache<String, Profile>,
) : KacheableViewModel<String, Profile>(application, cache) {

    fun loadProfile(id: String, fetch: suspend (String) -> Profile) {
        load(key = id, fetcher = fetch)
    }
}
