package io.github.slavikjunior.kache.store.room

import java.nio.file.Files

/** Runs the shared contract against a file-backed Room database on the JVM. */
class RoomStorageEngineTest : RoomStorageEngineTestBase() {

    private val directory = Files.createTempDirectory("kache-room").toFile()

    override fun createEngine(): RoomStorageEngine = createFromFile(
        dbFilePath = directory.resolve("kache-${counter++}.db").absolutePath,
    )

    private companion object {
        var counter = 0
    }
}