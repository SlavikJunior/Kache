package io.github.slavikjunior.kache.sample.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Envelope shared by every vPIC endpoint.
 *
 * Every property is declared even when the app does not use it, because
 * [io.github.slavikjunior.kache.storage.KotlinxJsonSerializer] parses strictly — it is
 * built on `Json { }` with the default `ignoreUnknownKeys = false`. That default is right
 * for a payload read back from disk, which this app wrote itself: a record whose shape no
 * longer matches fails loudly as `SerializationException` instead of silently losing
 * fields. Network responses are parsed by Ktor with `ignoreUnknownKeys = true`, so a field
 * added by the service does not break the screen; it only means the DTO below is worth
 * extending.
 *
 * @param count Number of results, as reported by the service.
 * @param message Service status message.
 * @param searchCriteria Echo of the query, null for list endpoints.
 * @param results Payload rows.
 */
@Serializable
internal data class NhtsaResponse<T>(
    @SerialName("Count") val count: Int = 0,
    @SerialName("Message") val message: String? = null,
    @SerialName("SearchCriteria") val searchCriteria: String? = null,
    @SerialName("Results") val results: List<T> = emptyList(),
)

/**
 * One row of `GetMakesForVehicleType/{type}`.
 *
 * Note the casing: this endpoint answers `MakeId`/`MakeName`, while
 * [GetModelsForMake][ModelDto] answers `Make_ID`/`Make_Name` in the same service. The two
 * shapes are not interchangeable, which is why each endpoint has its own DTO.
 */
@Serializable
internal data class MakeDto(
    @SerialName("MakeId") val id: Int,
    @SerialName("MakeName") val name: String,
    @SerialName("VehicleTypeId") val vehicleTypeId: Int,
    @SerialName("VehicleTypeName") val vehicleTypeName: String,
)

/** One row of `GetModelsForMake/{make}`. */
@Serializable
internal data class ModelDto(
    @SerialName("Make_ID") val makeId: Int,
    @SerialName("Make_Name") val makeName: String,
    @SerialName("Model_ID") val id: Int,
    @SerialName("Model_Name") val name: String,
)
