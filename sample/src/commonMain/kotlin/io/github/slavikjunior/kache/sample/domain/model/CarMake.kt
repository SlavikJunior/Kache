package io.github.slavikjunior.kache.sample.domain.model

import kotlinx.serialization.Serializable

/**
 * A car make as the UI understands it.
 *
 * Serializable because the cache holds this type. `KacheStateHolder` publishes exactly what
 * the cache stores, so the value on disk and the value the screen renders are the same
 * object; caching transport DTOs instead would either push DTOs into the view layer or need
 * a reverse mapper, and a reverse mapper cannot be written honestly — a DTO carries fields
 * this type drops.
 *
 * @param id Stable identifier from the catalogue.
 * @param name Display name, already trimmed.
 */
@Serializable
data class CarMake(
    val id: Int,
    val name: String,
)

/**
 * A model produced by one make.
 *
 * @param id Stable identifier from the catalogue.
 * @param name Display name.
 */
@Serializable
data class CarModel(
    val id: Int,
    val name: String,
)
