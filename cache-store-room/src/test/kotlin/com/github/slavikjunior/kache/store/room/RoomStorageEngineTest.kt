package com.github.slavikjunior.kache.store.room

import java.nio.file.Files

/**
 * JVM tests for [RoomStorageEngine].
 */
class RoomStorageEngineTest : RoomStorageEngineTestBase() {

    override suspend fun createTestEngine(): RoomStorageEngine {
        val tempFile = Files.createTempFile("kache-test-", ".db")
        tempFile.toFile().deleteOnExit()
        return RoomStorageEngineFactory.createFromFile(tempFile.toAbsolutePath().toString())
    }
}