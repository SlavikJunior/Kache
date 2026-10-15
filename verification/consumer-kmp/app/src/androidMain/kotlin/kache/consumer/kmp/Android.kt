package io.github.slavikjunior.kache.consumer.kmp

import android.content.Context
import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.storage.createFromContext as fileFromContext
import io.github.slavikjunior.kache.store.room.createFromContext as roomFromContext

/**
 * Android-only entry points. Both factories are named `createFromContext` in different
 * packages, which is exactly the kind of collision a consumer has to resolve — and both
 * have to be on the compile classpath for it to be resolvable at all.
 */
fun androidBackends(context: Context): List<StorageEngine> = listOf(
    fileFromContext(context, "consumer-file"),
    roomFromContext(context, "consumer.db"),
)
