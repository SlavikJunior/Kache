package io.github.slavikjunior.kache.store.room

import androidx.room.Room
import android.content.Context
import java.io.File

/**
 * Creates a [RoomStorageEngine] whose database lives in the app's private cache directory.
 *
 * A cache directory is where a cache belongs: Android excludes it from Auto Backup, and the
 * system is free to purge it under disk pressure. The files directory is neither — a
 * database left there is uploaded to the user's cloud backup, payloads and all, and restored
 * onto another device. `:cache-storage`'s `createFromContext` and this module's iOS factory
 * already put their data in a cache directory; this one now matches them instead of being the
 * odd one out.
 *
 * A database written by an earlier version, which lived in the files directory, is moved
 * across on first use, so upgrading does not silently discard the cache.
 *
 * @param context Any context; the application context is retained internally and only its
 *   cache directory is read.
 * @param databaseFileName File name of the database, not an absolute path.
 * @return Storage engine backed by Room.
 */
public fun createFromContext(
    context: Context,
    databaseFileName: String = DEFAULT_DATABASE_FILE_NAME,
): RoomStorageEngine {
    val application = context.applicationContext ?: context
    val dbFile = File(application.cacheDir, databaseFileName)

    // Best effort: a cache that cannot be moved is a cache that gets rebuilt, which is far
    // better than an engine that refuses to start.
    runCatching { moveDatabaseFilesIfPresent(File(application.filesDir, databaseFileName), dbFile) }

    val database = Room.databaseBuilder<KacheDatabase>(
        context = application,
        name = dbFile.absolutePath,
    )
        .setDriver(RoomStorageEngineFactory.driver)
        .addMigrations(*KACHE_MIGRATIONS)
        .build()
    return RoomStorageEngineFactory.create(database)
}

/**
 * Moves a database and its sidecar files from [from] to [to], when there is anything to move.
 *
 * The sidecars matter: a SQLite database in WAL mode keeps recent writes in `-wal` and `-shm`
 * alongside the main file, so moving the `.db` alone would silently drop them. A rollback
 * journal is handled too, for a database that never switched to WAL.
 *
 * An existing target wins. The move is what carries a cache across an upgrade, so it must
 * never be the thing that destroys a database already in the new location.
 *
 * @param from Database path in the old location.
 * @param to Database path in the new location.
 * @return true if at least one file was moved.
 */
internal fun moveDatabaseFilesIfPresent(from: File, to: File): Boolean {
    if (to.exists() || !from.exists()) return false

    to.parentFile?.mkdirs()

    var moved = false
    DATABASE_SIDECAR_SUFFIXES.forEach { suffix ->
        val source = File(from.path + suffix)
        if (!source.exists()) return@forEach

        val destination = File(to.path + suffix)
        if (source.renameTo(destination)) {
            moved = true
            return@forEach
        }

        // renameTo cannot cross filesystems, and the two directories are not guaranteed to
        // share one. A copy is not atomic, but this runs before the database is opened.
        runCatching {
            source.copyTo(destination, overwrite = true)
            source.delete()
            moved = true
        }
    }
    return moved
}

private val DATABASE_SIDECAR_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

private const val DEFAULT_DATABASE_FILE_NAME = "kache.db"
