package io.github.slavikjunior.kache.storage

import android.content.Context
import java.io.File

/**
 * Creates a [FileStorageEngine] rooted in the app's private cache directory.
 *
 * This is an Android-only convenience on top of [FileStorageEngine]'s path-based
 * constructor. It is a top-level function rather than a companion member because the
 * engine implementation itself is shared between the JVM and Android targets through
 * `jvmCommonMain`; keeping [Context] out of that shared class is what allows the two
 * platforms to share one copy of the code.
 *
 * The location is app-private, so cached data is removed with the app and is not
 * visible to other apps.
 *
 * @param context Any context; the application context is retained internally.
 * @param cacheDirName Subdirectory name within the app cache directory.
 */
public fun createFromContext(
    context: Context,
    cacheDirName: String = DEFAULT_CACHE_DIR,
): FileStorageEngine = FileStorageEngine(File(context.cacheDir, cacheDirName).absolutePath)

private const val DEFAULT_CACHE_DIR = "kache"