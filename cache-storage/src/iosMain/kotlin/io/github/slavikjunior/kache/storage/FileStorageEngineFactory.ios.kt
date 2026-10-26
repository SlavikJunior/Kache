package io.github.slavikjunior.kache.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * Creates a [FileStorageEngine] rooted in the app's Caches directory.
 *
 * The iOS counterpart of `createFromContext` on Android, and it exists for the same reason: a
 * caller should not have to know which Foundation constant names the right directory.
 * `NSCachesDirectory` is the directory the system may purge, which is exactly what a cache
 * wants — it survives a launch but never a full disk.
 *
 * The directory is asked for with `create = true` because it is not guaranteed to exist for a
 * process that has not yet been granted a full sandbox, such as a unit-test host: resolving a
 * path without creating it leaves the engine unable to open the lock-less record files, and
 * the engine deliberately creates its own subdirectory rather than resolving one.
 *
 * @param cacheDirName Subdirectory name within the Caches directory.
 * @param maxRecordBytes Largest record the engine will read or write.
 * @return Storage engine backed by one file per key.
 */
@OptIn(ExperimentalForeignApi::class)
public fun createFromCachesDirectory(
    cacheDirName: String = "kache",
    maxRecordBytes: Long = DEFAULT_MAX_RECORD_BYTES,
): FileStorageEngine {
    val cachesUrl = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
    ) { "The application caches directory is unavailable" }

    val cachesPath = requireNotNull(cachesUrl.path) {
        "The application caches directory has no path"
    }

    return FileStorageEngine(
        rootDirectory = joinPath(cachesPath, cacheDirName),
        maxRecordBytes = maxRecordBytes,
    )
}
