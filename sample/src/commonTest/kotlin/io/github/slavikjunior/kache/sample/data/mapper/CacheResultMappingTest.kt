package io.github.slavikjunior.kache.sample.data.mapper

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.KacheException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The mapping between the cached payload and the domain model is the only place where a
 * result can silently lose what the cache reported, so each variant is pinned here.
 */
class CacheResultMappingTest {

    @Test
    fun successKeepsTheOriginItWasReportedWith() {
        val mapped = CacheResult.Success(data = listOf(1, 2), origin = CacheOrigin.DISK_STALE)
            .mapData { values -> values.map { it * 10 } }

        assertEquals(CacheResult.Success(listOf(10, 20), CacheOrigin.DISK_STALE), mapped)
    }

    @Test
    fun loadingMapsTheStalePayloadAndKeepsTheFlag() {
        val source: CacheResult<List<Int>> = CacheResult.Loading(cachedData = listOf(1))

        val mapped = source.mapData { values -> values.map { it + 1 } }

        assertEquals(CacheResult.Loading(cachedData = listOf(2)), mapped)
    }

    @Test
    fun loadingWithoutPayloadStaysWithoutPayload() {
        val source: CacheResult<List<Int>> = CacheResult.Loading()

        val mapped = source.mapData { values -> values.map { it + 1 } }

        assertNull((mapped as CacheResult.Loading).cachedData)
    }

    @Test
    fun errorKeepsTheTypedFailureAndMapsTheFallback() {
        val failure = KacheException.NetworkException(IllegalStateException("offline"))

        val mapped = CacheResult.Error(error = failure, cachedData = listOf("a"))
            .mapData { values -> values.map { it.uppercase() } }

        val error = mapped as CacheResult.Error
        assertSame(failure, error.error, "the caller needs the typed failure, not a copy")
        assertEquals(listOf("A"), error.cachedData)
    }
}
