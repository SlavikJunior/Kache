package io.github.slavikjunior.kache.consumer.kmp

import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.store.room.createFromCachesDirectory

/**
 * iOS-only entry point from `:cache-store-room`'s `iosMain`, reached through the published
 * klib for `iosArm64` / `iosSimulatorArm64`.
 */
fun iosBackend(): StorageEngine = createFromCachesDirectory("consumer.db")
