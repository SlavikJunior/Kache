package io.github.slavikjunior.kache.consumer.kmp

import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.storage.FileStorageEngine
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import io.github.slavikjunior.kache.storage.createFromCachesDirectory
import io.github.slavikjunior.kache.store.room.createFromCachesDirectory as roomFromCachesDirectory
import kotlinx.serialization.builtins.serializer

/**
 * iOS-only entry points, reached through the published klibs for `iosArm64` and
 * `iosSimulatorArm64`. Compilation here is the proof: these declarations exist on iOS only in
 * the published artifacts, so a missing target, a wrong source set or a dependency that leaked
 * into `implementation` without being resolved fails this harness and nothing else.
 */

/** Room's backend, from `:cache-store-room`'s `iosMain`. */
fun iosBackend(): StorageEngine = roomFromCachesDirectory("consumer.db")

/**
 * The file backend on iOS.
 *
 * Before 1.0.0 this call did not compile: the engine lived in `jvmCommonMain`, so the iOS klib
 * of `:cache-storage` carried the serializers and nothing else, and an iOS app had to bring its
 * own durable tier.
 */
fun iosFileBackend(): StorageEngine = FileStorageEngine("/tmp/kache-consumer")

/** The convenience factory that resolves the Caches directory without leaking a Foundation type. */
fun iosFileBackendInCaches(): StorageEngine = createFromCachesDirectory("consumer")

/** Proves the serializer is reachable from the iOS klib of `:cache-storage`. */
val iosSerializer = KotlinxJsonSerializer(String.serializer())
