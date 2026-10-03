package io.github.slavikjunior.kache.store.room

/**
 * Runs the shared contract against a Room database in the iOS caches directory.
 *
 * The directory is created by the factory with `create = true`, which is what makes it
 * work at all: SQLite cannot create its own lock file next to a database whose parent
 * directory does not exist, and `create = false` fails with "Unable to open lock file".
 */
class RoomStorageEngineIosTest : RoomStorageEngineTestBase() {

    override fun createEngine(): RoomStorageEngine =
        createFromCachesDirectory(databaseFileName = "kache-test-${counter++}.db")

    private companion object {
        var counter = 0
    }
}