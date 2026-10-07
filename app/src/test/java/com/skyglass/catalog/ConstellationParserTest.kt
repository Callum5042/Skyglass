package com.skyglass.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConstellationParserTest {

    @Test
    fun parsesFiguresAndSkipsCommentsAndBlankLines() {
        val figures = ConstellationParser.parse(
            sequenceOf(
                "# a comment",
                "",
                "Cru|Crux|Crucis|4730-4763/4853-4656",
                "Ser|Serpens Caput|Serpentis|5854-5867-5879",
            ),
        )
        assertEquals(2, figures.size)
        assertEquals(Constellation("Cru", "Crux", "Crucis", listOf(listOf(4730, 4763), listOf(4853, 4656))), figures[0])
        assertEquals(setOf(4730, 4763, 4853, 4656), figures[0].starHrs)
        assertEquals("Serpens Caput", figures[1].name)
    }

    @Test
    fun rejectsMalformedLines() {
        assertThrows(IllegalArgumentException::class.java) {
            ConstellationParser.parse(sequenceOf("Cru|Crux|4730-4763"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConstellationParser.parse(sequenceOf("Cru|Crux|Crucis|4730-abc"))
        }
    }

    @Test
    fun parsesProperNames() {
        val names = ProperNameParser.parse(sequenceOf("# header", "2491|Sirius|Alp CMa", "", "5062|Alcor|80 UMa"))
        assertEquals(listOf(ProperName(2491, "Sirius", "Alp CMa"), ProperName(5062, "Alcor", "80 UMa")), names)
    }

    @Test
    fun rejectsMalformedProperNames() {
        assertThrows(IllegalArgumentException::class.java) { ProperNameParser.parse(sequenceOf("Sirius|Alp CMa")) }
        assertThrows(IllegalArgumentException::class.java) { ProperNameParser.parse(sequenceOf("x|Sirius|Alp CMa")) }
    }
}
