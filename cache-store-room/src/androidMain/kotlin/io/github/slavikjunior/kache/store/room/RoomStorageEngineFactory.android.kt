package io.github.slavikjunior.kache.store.room

import androidx.room.Room
import android.content.Context
import java.io.File

/**
 * Creates a [RoomStorageEngine] whose database lives in the app's private files
 * directory.
 *
 * The location is app-private, so cached data is removed together with the app and is
 * not visible to other apps.
 *
 * @param context Any context; only the files directory is read from it.
 * @param databaseFileName File name of the database, not an absolute path.
 * @return Storage engine backed by Room.
 */
public fun createFromContext(
    context: Context,
    databaseFileName: String = DEFAULT_DATABASE_FILE_NAME,
): RoomStorageEngine {
    val dbFile = File(context.filesDir, databaseFileName)
    val database = Room.databaseBuilder<KacheDatabase>(
        context = context,
        name = dbFile.absolutePath,
    )
        .setDriver(RoomStorageEngineFactory.driver)
        .build()
    return RoomStorageEngineFactory.create(database)
}

private const val DEFAULT_DATABASE_FILE_NAME = "kache.db"