package io.github.slavikjunior.kache.store.room

import androidx.room.Room
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * Creates a [RoomStorageEngine] whose database lives in the iOS Caches directory.
 *
 * iOS sandboxes applications, so a database cannot be placed at an arbitrary absolute
 * path; the location has to come from the system. The Caches directory is used rather
 * than Documents because this is cache data: iOS is free to purge it under disk
 * pressure, which is the correct behaviour for a cache and is what
 * `NSCachesDirectory` is meant for.
 *
 * The directory is created if missing, because it is not guaranteed to exist for a
 * process that has not yet been granted a full sandbox, such as a unit-test host.
 *
 * @param databaseFileName File name of the database, not a path.
 * @return Storage engine backed by Room.
 */
@OptIn(ExperimentalForeignApi::class)
public fun createFromCachesDirectory(
    databaseFileName: String = DEFAULT_DATABASE_FILE_NAME,
): RoomStorageEngine {
    val cachesDirectory: NSURL =
        requireNotNull(NSFileManager.defaultManager.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )) { "Unable to resolve the iOS Caches directory" }

    val database = Room.databaseBuilder<KacheDatabase>(
        name = requireNotNull(cachesDirectory.path) + "/" + databaseFileName,
    )
        .setDriver(RoomStorageEngineFactory.driver)
        .build()
    return RoomStorageEngineFactory.create(database)
}

private const val DEFAULT_DATABASE_FILE_NAME = "kache.db"