package io.github.slavikjunior.kache.sample.domain.model

import kotlinx.serialization.Serializable

/**
 * One measured dimension of a vehicle.
 *
 * @param label What was measured, in the words the source uses in its own documentation.
 * @param value Number as the service reports it. Kept as text on purpose: the source has
 *   optional rows and blank values, and parsing them to numbers would invent precision the
 *   data does not have.
 * @param unit Unit the value is expressed in, or null for ratios such as weight
 *   distribution which carry their own symbol.
 */
@Serializable
data class CarSpec(
    val label: String,
    val value: String,
    val unit: String? = null,
)

/**
 * The measurements of one trim of a model in one model year.
 *
 * Serializable for the same reason as [CarMake]: this is what the cache stores, so it is
 * also what the view layer publishes.
 *
 * @param trim Trim as the source names it, often including body style and engine
 *   (`ACCORD 2DR COUPE EX-L V6`). Empty when the row carries no model name.
 * @param dataYear Two-digit year the row was compiled for, as the source reports it.
 * @param specs Dimensions in a stable order, not the order they arrived in.
 */
@Serializable
data class CarSpecSheet(
    val trim: String,
    val dataYear: String? = null,
    val specs: List<CarSpec> = emptyList(),
)
