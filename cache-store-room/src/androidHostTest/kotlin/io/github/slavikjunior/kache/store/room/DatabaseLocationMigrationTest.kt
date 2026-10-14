package io.github.slavikjunior.kache.store.room

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * The upgrade path for the database location, tested as plain file movement.
 *
 * The engine itself cannot be built here: [createFromContext] goes through the bundled SQLite
 * driver, whose JNI library is compiled for Android ABIs and will not load in a host JVM. The
 * file movement is the part that carries a user's cache across the upgrade, and it does not
 * need a database to be exercised, so it is tested on its own rather than left unverified.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseLocationMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "kache.db").delete()
        File(context.cacheDir, "kache.db").delete()
    }

    private fun legacy(name: String): File = File(context.filesDir, name)
    private fun current(name: String): File = File(context.cacheDir, name)

    @Test
    fun aLegacyDatabaseIsMovedOutOfTheFilesDirectory() {
        val from = legacy("kache.db").apply { writeText("main") }
        val wal = File(from.path + "-wal").apply { writeText("wal") }
        val shm = File(from.path + "-shm").apply { writeText("shm") }
        val to = current("kache.db")

        assertTrue(moveDatabaseFilesIfPresent(from, to))

        assertEquals("main", to.readText())
        // The sidecars carry the writes that have not been checkpointed yet; moving only the
        // .db would silently lose them.
        assertEquals("wal", File(to.path + "-wal").readText())
        assertEquals("shm", File(to.path + "-shm").readText())
        assertFalse(from.exists(), "the old database must not be left behind")
        assertFalse(wal.exists())
        assertFalse(shm.exists())
    }

    @Test
    fun anExistingTargetIsNeverOverwritten() {
        val from = legacy("kache.db").apply { writeText("old") }
        val to = current("kache.db").apply { writeText("current") }

        assertFalse(moveDatabaseFilesIfPresent(from, to))

        // The move exists to preserve a cache, so it must never be what destroys one.
        assertEquals("current", to.readText())
        assertTrue(from.exists(), "nothing may be consumed when the move is declined")
    }

    @Test
    fun nothingToMoveIsReportedAsSuch() {
        val from = legacy("kache.db")
        val to = current("kache.db")

        assertFalse(moveDatabaseFilesIfPresent(from, to))

        assertFalse(to.exists(), "a first install must not produce an empty database")
    }

    @Test
    fun aDatabaseWithoutSidecarsStillMoves() {
        val from = legacy("kache.db").apply { writeText("main") }
        val to = current("kache.db")

        assertTrue(moveDatabaseFilesIfPresent(from, to))

        assertEquals("main", to.readText())
        assertFalse(File(to.path + "-wal").exists(), "no sidecar may be invented")
    }
}
