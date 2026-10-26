package io.github.slavikjunior.kache.sample.data.mapper

import io.github.slavikjunior.kache.sample.data.remote.dto.CanadianSpecsDto
import io.github.slavikjunior.kache.sample.data.remote.dto.NameValueDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The source answers specification codes (`OL`, `WB`, `CW`…) rather than named fields, so
 * this mapper is the only place that knows what a code means. A wrong label is worse than a
 * missing row: it reads as a fact.
 */
class SpecMapperTest {

    @Test
    fun mapsCodesToDocumentedLabelsWithTheirUnits() {
        val sheet = requireNotNull(
            CanadianSpecsDto(
                specs = listOf(
                    NameValueDto("MODEL", "ACCORD 2DR COUPE EX-L V6"),
                    NameValueDto("MYR", "15"),
                    NameValueDto("OL", "480"),
                    NameValueDto("CW", "1603"),
                    NameValueDto("WD", "61/39"),
                ),
            ).toDomain(),
        )

        assertEquals("ACCORD 2DR COUPE EX-L V6", sheet.trim)
        assertEquals("15", sheet.dataYear)
        assertEquals(
            listOf("Overall length", "Curb weight", "Weight distribution (front/rear)"),
            sheet.specs.map { it.label },
        )
        assertEquals(listOf("cm", "kg", "%"), sheet.specs.map { it.unit })
    }

    @Test
    fun ordersDimensionsTheSameWayForEveryRowSoTrimsCanBeCompared() {
        // Deliberately out of order, and missing CW: a row that arrives sorted differently
        // must not produce a differently sorted table.
        val sheet = requireNotNull(
            CanadianSpecsDto(
                specs = listOf(
                    NameValueDto("WD", "61/39"),
                    NameValueDto("WB", "272"),
                    NameValueDto("OL", "480"),
                ),
            ).toDomain(),
        )

        assertEquals(listOf("Overall length", "Wheelbase", "Weight distribution (front/rear)"), sheet.specs.map { it.label })
    }

    @Test
    fun dropsCodesTheSampleDoesNotKnowRatherThanPrintingThem() {
        val sheet = requireNotNull(
            CanadianSpecsDto(
                specs = listOf(
                    NameValueDto("OL", "480"),
                    NameValueDto("ZZ", "17"),
                ),
            ).toDomain(),
        )

        assertEquals(1, sheet.specs.size)
        assertTrue(sheet.specs.none { it.value == "17" }, "an unknown code must not reach the table")
    }

    @Test
    fun blankValuesAreSkipped() {
        val sheet = requireNotNull(
            CanadianSpecsDto(
                specs = listOf(
                    NameValueDto("OL", "480"),
                    NameValueDto("OW", "   "),
                ),
            ).toDomain(),
        )

        assertEquals(listOf("Overall length"), sheet.specs.map { it.label })
    }

    @Test
    fun aRowWithNoKnownDimensionsBecomesNullInsteadOfAnEmptyCard() {
        assertNull(CanadianSpecsDto(specs = listOf(NameValueDto("MODEL", "X"))).toDomain())
        assertNull(CanadianSpecsDto().toDomain())
    }

    @Test
    fun codesAreMatchedRegardlessOfCaseBecauseTheSourceIsNotConsistent() {
        val sheet = requireNotNull(
            CanadianSpecsDto(
                specs = listOf(
                    NameValueDto("ol", "480"),
                    NameValueDto("Model", "CIVIC"),
                    NameValueDto("myr", "22"),
                ),
            ).toDomain(),
        )

        assertEquals("CIVIC", sheet.trim)
        assertEquals("22", sheet.dataYear)
        assertEquals("Overall length", sheet.specs.single().label)
    }
}
