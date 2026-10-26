package io.github.slavikjunior.kache.sample.data.remote

import io.github.slavikjunior.kache.sample.data.remote.dto.CanadianSpecsDto
import io.github.slavikjunior.kache.sample.data.remote.dto.MakeDto
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto

/**
 * Catalogue source, as the repository sees it.
 *
 * An interface rather than the concrete client, so the repository can be tested against a
 * fake without an HTTP stack, and so replacing vPIC with another service is a matter of a
 * new implementation. The repository's cache is configured around this contract; nothing
 * in it knows that responses arrive over HTTP.
 *
 * Implementations throw on failure. Turning that into a cache result is the library's job,
 * not the transport's.
 */
internal interface CarRemoteDataSource {

    /** Every make that produces passenger cars. */
    suspend fun carMakes(): List<MakeDto>

    /** Models produced by [makeName]. */
    suspend fun modelsForMake(makeName: String): List<ModelDto>

    /**
     * Canadian Vehicle Specifications for one model and model year.
     *
     * A model year is required because the source is a per-year compilation: the same
     * name means different vehicles in 1998 and 2026. The list may be empty — the dataset
     * does not cover every model/year combination.
     */
    suspend fun specificationsFor(makeName: String, modelName: String, year: Int): List<CanadianSpecsDto>
}
