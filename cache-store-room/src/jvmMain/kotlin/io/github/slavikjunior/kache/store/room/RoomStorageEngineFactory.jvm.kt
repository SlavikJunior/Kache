package io.github.slavikjunior.kache.store.room

import androidx.room.Room
import java.io.File

/**
 * Creates a [RoomStorageEngine] with a file-based database on the JVM.
 *
 * @param dbFilePath Absolute path to the database file (e.g. `/tmp/kache.db`).
 *   The parent directory must exist.
 * @return Storage engine backed by Room.
 */
public fun createFromFile(dbFilePath: String): RoomStorageEngine {
    val dbFile = File(dbFilePath)
    require(dbFile.parentFile?.exists() == true) {
        "Parent directory does not exist: ${dbFile.parentFile?.absolutePath}"
    }
    val database = Room.databaseBuilder<KacheDatabase>(
        name = dbFile.absolutePath,
    )
        .setDriver(RoomStorageEngineFactory.driver)
        .build()
    return RoomStorageEngineFactory.create(database)
}