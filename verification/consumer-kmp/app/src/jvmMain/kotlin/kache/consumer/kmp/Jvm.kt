package io.github.slavikjunior.kache.consumer.kmp

import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.storage.FileStorageEngine
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import io.github.slavikjunior.kache.store.room.createFromFile
import kotlinx.serialization.builtins.serializer

/**
 * JVM-only entry points. `createFromFile` lives in `:cache-store-room`'s `jvmMain`, and
 * `FileStorageEngine` in `:cache-storage`'s `jvmCommonMain` — the serializers in that module
 * are published for every target, but the file engine needs `java.io`.
 */
fun jvmBackends(directoryPath: String): List<StorageEngine> = listOf(
    FileStorageEngine(directoryPath),
    createFromFile("$directoryPath/room.db"),
)

/** `KSerializer` is used here without an explicit serialization dependency. */
val jvmSerializer = KotlinxJsonSerializer(String.serializer())
