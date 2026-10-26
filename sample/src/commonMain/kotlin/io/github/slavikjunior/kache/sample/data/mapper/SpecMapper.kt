package io.github.slavikjunior.kache.sample.data.mapper

import io.github.slavikjunior.kache.sample.data.remote.dto.CanadianSpecsDto
import io.github.slavikjunior.kache.sample.domain.model.CarSpec
import io.github.slavikjunior.kache.sample.domain.model.CarSpecSheet

/**
 * Turns a Canadian Vehicle Specifications row into a displayable sheet.
 *
 * The endpoint returns dimensions as name/value pairs keyed by codes (`OL`, `WB`, `CW`…),
 * so the meaning of a value lives in the source's documentation rather than in the payload.
 * The labels below are copied from that documentation, and the order is fixed: a reading
 * order that changes per row would make two trims impossible to compare.
 *
 * Unknown codes are dropped rather than printed raw — a row that gains a code should not
 * put `XZ: 12` in front of a user. A row that maps to nothing becomes `null`, and the
 * repository reports it as an empty result.
 */
internal fun CanadianSpecsDto.toDomain(): CarSpecSheet? {
    val byCode = specs.associate { it.name.uppercase() to it.value.trim() }

    val dimensions = SPEC_ORDER.mapNotNull { code ->
        val value = byCode[code]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        CarSpec(
            label = requireNotNull(SPEC_LABELS[code]),
            value = value,
            unit = SPEC_UNITS[code] ?: DEFAULT_UNIT,
        )
    }
    if (dimensions.isEmpty()) return null

    return CarSpecSheet(
        trim = byCode["MODEL"].orEmpty(),
        dataYear = byCode["MYR"]?.takeIf { it.isNotEmpty() },
        specs = dimensions,
    )
}

/**
 * Presentation order, following the source's own table: body envelope first, then
 * overhangs and tracks, then the two figures with their own units.
 */
private val SPEC_ORDER = listOf(
    "OL",
    "OW",
    "OH",
    "WB",
    "TWF",
    "TWR",
    "A",
    "B",
    "C",
    "D",
    "E",
    "F",
    "G",
    "CW",
    "WD",
)

/** Labels verbatim from the vPIC documentation of the endpoint. */
private val SPEC_LABELS = mapOf(
    "OL" to "Overall length",
    "OW" to "Overall width",
    "OH" to "Overall height",
    "WB" to "Wheelbase",
    "TWF" to "Front track width",
    "TWR" to "Rear track width",
    "A" to "Front bumper to windshield base",
    "B" to "Rear bumper to backlight base",
    "C" to "Side glass height",
    "D" to "Side glass base to rocker panel",
    "E" to "Width between side rails",
    "F" to "Front overhang",
    "G" to "Rear overhang",
    "CW" to "Curb weight",
    "WD" to "Weight distribution (front/rear)",
)

/** Units per code. Everything the source reports in centimetres is the fallback. */
private val SPEC_UNITS = mapOf(
    "CW" to "kg",
    "WD" to "%",
)

/** Default unit for this dataset: every dimension the endpoint reports is in centimetres. */
private const val DEFAULT_UNIT = "cm"
