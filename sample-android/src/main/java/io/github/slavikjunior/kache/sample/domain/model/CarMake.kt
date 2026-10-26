package io.github.slavikjunior.kache.sample.domain.model

/**
 * A car make as the UI understands it.
 *
 * Deliberately free of serialization annotations: the cache stores the transport DTOs in
 * the data layer, and domain models stay plain Kotlin. Adding a field to the API response
 * therefore changes a DTO and a mapper, not the domain.
 *
 * @param id Stable identifier from the catalogue.
 * @param name Display name, already trimmed.
 */
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
data class CarModel(
    val id: Int,
    val name: String,
)
