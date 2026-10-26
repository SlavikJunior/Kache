package io.github.slavikjunior.kache.sample.data.mapper

import io.github.slavikjunior.kache.core.CacheResult

/**
 * Maps the payload of a [CacheResult] without losing what the cache reported about it.
 *
 * The origin, the loading flag and the error survive the mapping, because those are the
 * properties the screen displays. Dropping them here would turn every result into "some
 * data" and hide exactly what the sample is meant to show.
 *
 * @param transform Converts the cached payload into its domain shape.
 */
internal inline fun <T, R> CacheResult<T>.mapData(transform: (T) -> R): CacheResult<R> = when (this) {
    is CacheResult.Success -> CacheResult.Success(data = transform(data), origin = origin)
    is CacheResult.Loading -> CacheResult.Loading(cachedData = cachedData?.let(transform))
    is CacheResult.Error -> CacheResult.Error(error = error, cachedData = cachedData?.let(transform))
}
