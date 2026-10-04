package io.github.slavikjunior.kache.store.room

import kotlin.random.Random

/**
 * Runs the shared contract against a Room database in the iOS caches directory.
 *
 * The directory is created by the factory with `create = true`, which is what makes it
 * work at all: SQLite cannot create its own lock file next to a database whose parent
 * directory does not exist, and `create = false` fails with "Unable to open lock file".
 */
class RoomStorageEngineIosTest : RoomStorageEngineTestBase() {

    override fun createEngine(): RoomStorageEngine =
        createFromCachesDirectory(databaseFileName = "kache-test-$RUN_ID-${counter++}.db")

    private companion object {
        /**
         * Distinguishes this run from every earlier one.
         *
         * The simulator's Caches directory outlives a single test run, so a counter alone is
         * not enough: it restarts at zero and hands out names that a previous run already
         * filled. Every test whose name collided then saw rows it had never written — which
         * looked like a database bug and was not one. A per-run prefix makes the names unique
         * across runs, and the leftover files are exactly what iOS is entitled to purge from
         * a caches directory.
         */
        val RUN_ID: Long = Random.nextLong(1L, Int.MAX_VALUE.toLong())

        var counter = 0
    }
}
