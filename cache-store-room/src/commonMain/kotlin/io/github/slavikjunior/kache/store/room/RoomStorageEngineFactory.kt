package io.github.slavikjunior.kache.store.room

import androidx.room.Room
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Factory for creating [RoomStorageEngine] instances.
 *
 * Uses the bundled native SQLite driver ([BundledSQLiteDriver]), so no external
 * JDBC driver or Android framework is required on any target.
 *
 * Only the mechanics that do not depend on how a database location is spelled live
 * here. Resolving a location is platform-specific and therefore lives in separate
 * top-level functions: `createFromFile` on the JVM, `createFromContext` on Android and
 * `createFromDocumentsDirectory` on iOS.
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
}