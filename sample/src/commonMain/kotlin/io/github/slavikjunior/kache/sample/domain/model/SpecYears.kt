package io.github.slavikjunior.kache.sample.domain.model

/**
 * Model years the sample offers for specifications.
 *
 * A compile-time range rather than one derived from the clock: the app deliberately has no
 * date library, and the source compiles its data once a year. [LATEST_SPEC_YEAR] is the
 * newest year NHTSA has published; bump it when a new one ships rather than silently
 * pretending the newest year is "now".
 */
const val LATEST_SPEC_YEAR: Int = 2026

/** Oldest model year the sample offers. The source itself starts at 1971. */
const val EARLIEST_SPEC_YEAR: Int = 1998

/** Years in display order, newest first. */
val SPEC_YEARS: List<Int> = (LATEST_SPEC_YEAR downTo EARLIEST_SPEC_YEAR).toList()
