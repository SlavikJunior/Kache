package io.github.slavikjunior.kache.store.room

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Proves that a database written by schema version 1 is carried forward rather than dropped.
 *
 * The version 1 table is created with raw SQL rather than through Room, because Room can only
 * ever build the current version. That is the whole point: it reproduces a file a real user
 * already has on disk, including the fact that it has no `last_accessed_at` column at all.
 */
class KacheMigrationTest {

    @Test
    fun aVersionOneDatabaseIsMigratedAndItsRowsSurvive() = runTest {
        val directory = Files.createTempDirectory("kache-migration").toFile()
        val dbFile = directory.resolve("legacy.db")
        createVersionOneDatabase(dbFile.absolutePath)

        val engine = createFromFile(dbFile.absolutePath)
        val read = assertNotNull(engine.get("legacy-key"), "a row from version 1 must survive the migration")

        assertEquals("old-value", read.data.decodeToString())
        assertEquals(1_000L, read.createdAt)
    }

    @Test
    fun migratedRowsGetTheCreationTimeAsTheirAccessTime() = runTest {
        val directory = Files.createTempDirectory("kache-migration-backfill").toFile()
        val dbFile = directory.resolve("legacy-backfill.db")
        createVersionOneDatabase(dbFile.absolutePath)

        val engine = createFromFile(dbFile.absolutePath)

        // Backfilled from created_at, not left at the column default of 0. A zero would make
        // every migrated row the least recently used one, so the first eviction after an
        // upgrade would throw away the whole existing cache.
        assertEquals(1_000L, assertNotNull(engine.get("legacy-key")).lastAccessedAt)
    }

    @Test
    fun aMigratedRowWithATtlStillExpires() = runTest {
        val directory = Files.createTempDirectory("kache-migration-ttl").toFile()
        val dbFile = directory.resolve("legacy-ttl.db")
        createVersionOneDatabase(dbFile.absolutePath)

        val engine = createFromFile(dbFile.absolutePath)

        assertEquals(1L, engine.removeExpired(now = 5_000L), "the TTL column must be honoured after migrating")
        assertEquals(0L, engine.size())
    }

    /**
     * Writes a database shaped exactly like the version 1 schema, with one row in it.
     *
     * The `room_master_table` is what makes this look like a database Room has seen before.
     * Without it Room treats the file as empty, runs its `onCreate` path, and never reaches
     * the migration — which is precisely the mistake this test exists to catch. The hash is
     * the one recorded in `schemas/…/1.json`; if that schema is ever regenerated the value
     * here goes stale and this test fails loudly rather than passing by accident.
     */
    private fun createVersionOneDatabase(path: String) {
        BundledSQLiteDriver().open(path).use { connection ->
            connection.prepare(
                "CREATE TABLE IF NOT EXISTS cache_entries (" +
                    "cache_key TEXT NOT NULL, data BLOB NOT NULL, created_at INTEGER NOT NULL, " +
                    "ttl_millis INTEGER, PRIMARY KEY(cache_key))"
            ).use { it.step() }

            connection.prepare(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            ).use { it.step() }

            connection.prepare(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)"
            ).use { statement ->
                statement.bindText(1, VERSION_ONE_IDENTITY_HASH)
                statement.step()
            }

            // Room reads the schema version from user_version, not from its own table. Without
            // this it considers the file a version 0 database, runs onCreate, and never migrates.
            connection.prepare("PRAGMA user_version = 1").use { it.step() }

            connection.prepare(
                "INSERT INTO cache_entries (cache_key, data, created_at, ttl_millis) VALUES (?, ?, ?, ?)"
            ).use { statement ->
                statement.bindText(1, "legacy-key")
                statement.bindBlob(2, "old-value".encodeToByteArray())
                statement.bindLong(3, 1_000L)
                statement.bindLong(4, 100L)
                statement.step()
            }
        }
    }

    private companion object {
        /** From `schemas/io.github.slavikjunior.kache.store.room.KacheDatabase/1.json`. */
        const val VERSION_ONE_IDENTITY_HASH = "11aa2feb8ee99f7305984f3bce2cacb3"
    }
}
