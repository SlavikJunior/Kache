package io.github.slavikjunior.kache.sample.domain.repository

import io.github.slavikjunior.kache.core.CacheStrategy

/**
 * One read as the data layer defines it: what to read, how to balance cache against network,
 * and how to fetch.
 *
 * It exists because the strategy is a data-layer decision while the read itself is performed
 * by the view layer's `KacheStateHolder`. Handing over three pieces separately would let a
 * caller pair a key with the wrong strategy; bundling them makes the pairing the data layer's
 * choice and keeps the view layer free of cache policy:
 *
 * ```
 * val request = repository.modelsRequest(make.name)
 * holder.load(request.key, request.fetch, request.strategy)
 * ```
 *
 * @param K Key type accepted by the cache.
 * @param V Value type the cache holds.
 * @property key Record key. Already normalised, so two spellings cannot occupy two records.
 * @property strategy How cached and fetched data are balanced.
 * @property fetch Loads fresh data. Called only when [strategy] needs it.
 */
class CacheRequest<K, V>(
    val key: K,
    val strategy: CacheStrategy,
    val fetch: suspend (K) -> V,
)
