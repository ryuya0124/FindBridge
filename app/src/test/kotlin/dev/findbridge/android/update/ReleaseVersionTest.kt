package dev.findbridge.android.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun releaseCandidatesUseNumericOrderAndStableWins() {
        val versions =
            listOf(
                "3.3.0-alpha1",
                "3.3.0-beta1",
                "3.3.0-rc2",
                "3.3.0-rc9",
                "3.3.0-rc10",
                "3.3.0",
                "3.3.1-alpha1",
                "3.10.0",
            )
        assertEquals(
            versions.map { ReleaseVersion.parse(it) },
            versions.reversed().map { ReleaseVersion.parse(it)!! }.sorted(),
        )
    }

    @Test
    fun malformedOrPathLikeTagsAreRejected() {
        listOf(
                "3.3",
                "3.3.0-rc0",
                "03.3.0",
                "3.3.0/evil",
                "3.3.0-rc1?x=y",
                "3.3.0-rc1\n",
                "9999999999999.0.0",
            )
            .forEach { assertNull(it, ReleaseVersion.parse(it)) }
        assertEquals(ReleaseVersion.parse("3.3.0-rc10"), ReleaseVersion.parse("v3.3.0-rc10"))
    }
}
