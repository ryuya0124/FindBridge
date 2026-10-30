package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class FindingCryptoTest {
    private val irk = ByteArray(16) { it.toByte() }

    @Test
    fun sessionAndDiscoveryKeyDerivationsMatchApplePrimitiveFixtures() {
        assertArrayEquals(
            hex("7804b64218d58ff5d786486e71c480f61acbaf036ece66c672d9a976ad6178d2"),
            FindingCrypto.deriveSessionKey(irk, hex("1011121314151617")),
        )
        // Independently emitted by tools/verify_uwb_crypto.py, using host CommonCrypto.
        assertArrayEquals(
            hex("7a04aa48de2beabf511facb4071eeab8"),
            FindingCrypto.deriveUnbucketedDiscoverySecret(ByteArray(32) { (0xa0 + it).toByte() }),
        )
    }

    @Test
    fun sipHashAndRoleResponsesMatchCoreUtilsFixtures() {
        assertArrayEquals(hex("310e0edd47db6f72"), FindingCrypto.sipHash24(irk, byteArrayOf()))
        assertArrayEquals(hex("643439df0fcfafdd"), FindingCrypto.addressForIrk(irk))
        assertArrayEquals(
            hex("8141dcdfb57c"),
            FindingCrypto.mutualAuthResponse(irk, hex("202122"), hex("303132")),
        )
        assertArrayEquals(
            hex("bdd034c197ec"),
            FindingCrypto.mutualAuthResponse(irk, hex("303132"), hex("202122")),
        )
        val vectors =
            javaClass.getResourceAsStream("/uwb-siphash24-vectors.txt")!!.bufferedReader().use {
                it.readLines()
            }
        assertEquals(64, vectors.size)
        for ((length, vector) in vectors.withIndex()) {
            assertArrayEquals(
                hex(vector),
                FindingCrypto.sipHash24(irk, ByteArray(length) { it.toByte() }),
            )
        }
    }

    @Test
    fun keysAndChallengesHaveTheObservedLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            FindingCrypto.deriveSessionKey(ByteArray(15), ByteArray(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FindingCrypto.deriveSessionKey(irk, ByteArray(7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FindingCrypto.mutualAuthResponse(irk, ByteArray(4), ByteArray(3))
        }
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
