package io.github.jqssun.airplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    private fun version(text: String) = requireNotNull(UpdateVersion.parse(text))

    @Test fun previewNumbersAndReleasePromotionHaveSemanticOrder() {
        val ordered = listOf("0.5.4-preview.2", "0.5.4-preview.10", "0.5.4-rc.1", "0.5.4", "0.6.0-preview.1")
        ordered.zipWithNext().forEach { (older, newer) ->
            assertTrue("$older should precede $newer", version(older) < version(newer))
        }
    }

    @Test fun numericIdentifiersDoNotUseLexicalOrderOrOverflow() {
        assertTrue(version("0.9.0") < version("0.10.0"))
        assertTrue(version("99999999999999999999.0.0") < version("100000000000000000000.0.0"))
        assertTrue(version("1.0.0-99999999999999999999") < version("1.0.0-100000000000000000000"))
    }

    @Test fun prereleaseIdentifiersFollowNumericAndPrefixRules() {
        assertTrue(version("1.0.0-7") < version("1.0.0-alpha"))
        assertTrue(version("1.0.0-alpha") < version("1.0.0-alpha.1"))
        assertTrue(version("1.0.0-alpha.1") < version("1.0.0-alpha.beta"))
    }

    @Test fun tagPrefixIsNormalizedAndBuildMetadataDoesNotChangeOrder() {
        assertEquals("0.6.0-preview.1", version("v0.6.0-preview.1").value)
        assertEquals(0, version("0.6.0+build.1").compareTo(version("0.6.0+build.2")))
    }

    @Test fun malformedVersionsCannotBecomeUpdateCandidates() {
        listOf("", "0.6", "01.6.0", "0.6.0-preview.01", "0.6.0-", "0.6.0+",
            " 0.6.0", "0.6.0\n", "0.6.0/other", "1".repeat(201) + ".0.0")
            .forEach { assertNull(it, UpdateVersion.parse(it)) }
    }
}
