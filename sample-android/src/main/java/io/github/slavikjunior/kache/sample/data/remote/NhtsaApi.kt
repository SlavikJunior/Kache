package io.github.slavikjunior.kache.sample.data.remote

import io.github.slavikjunior.kache.sample.data.remote.dto.MakeDto
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto
import io.github.slavikjunior.kache.sample.data.remote.dto.NhtsaResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom

/**
 * Thin client over the NHTSA vPIC API.
 *
 * No caching, no mapping and no retries live here: the cache is wired around this class by
 * the repository, and retries are the cache's `RetryPolicy`. A transport class that also
 * decided when to reuse data would make both behaviours impossible to test apart.
 *
 * Failures are not caught. Ktor throws for a non-2xx response (`expectSuccess` is set where
 * the client is built) and for transport problems, and the cache turns a throwing fetcher
 * into a reported network failure — so the sample gets the library's error path rather than
 * a second, home-grown one.
 *
 * @param client Client configured with a JSON content negotiation.
 * @param baseUrl Root of the service, without a trailing slash.
 */
internal class NhtsaApi(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : CarRemoteDataSource {

    /** Every make that produces passenger cars. */
    override suspend fun carMakes(): List<MakeDto> = client.get {
        url {
            takeFrom(baseUrl)
            appendPathSegments("api", "vehicles", "GetMakesForVehicleType", CAR_VEHICLE_TYPE)
            parameters.append(QUERY_FORMAT, "json")
        }
    }.body<NhtsaResponse<MakeDto>>().results

    /**
     * Models produced by [makeName].
     *
     * The name goes through [appendPathSegments], so a make such as `ASTON MARTIN` is
     * encoded rather than pasted into the path.
     */
    override suspend fun modelsForMake(makeName: String): List<ModelDto> = client.get {
        url {
            takeFrom(baseUrl)
            appendPathSegments("api", "vehicles", "GetModelsForMake", makeName)
            parameters.append(QUERY_FORMAT, "json")
        }
    }.body<NhtsaResponse<ModelDto>>().results

    private companion object {
        const val DEFAULT_BASE_URL = "https://vpic.nhtsa.dot.gov"
        const val CAR_VEHICLE_TYPE = "car"
        const val QUERY_FORMAT = "format"
    }
}
