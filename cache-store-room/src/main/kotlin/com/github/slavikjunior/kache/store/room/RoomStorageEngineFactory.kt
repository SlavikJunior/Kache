package com.github.slavikjunior.kache.store.room

import androidx.room.Room
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * Factory for creating [RoomStorageEngine] instances on JVM.
 *
 * Uses the bundled native SQLite driver ([BundledSQLiteDriver]), so no external
 * JDBC driver or Android framework is required.
 */
public object RoomStorageEngineFactory {

    /**
     * Default driver for the current platform.
     */
    internal val driver: SQLiteDriver by lazy { BundledSQLiteDriver() }

    /**
     * Creates a [RoomStorageEngine] from an existing [KacheDatabase] instance.
     *
     * Useful when you need full control over the Room database configuration
     * (migrations, callbacks, journal mode, etc.).
     *
     * @param database Pre-configured Room database.
     * @return Storage engine backed by the provided database.
     */
    public fun create(database: KacheDatabase): RoomStorageEngine {
        return RoomStorageEngine(database)
    }

    /**
     * Creates a [RoomStorageEngine] with a file-based database.
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
            .setDriver(driver)
            .build()
        return create(database)
    }
}
