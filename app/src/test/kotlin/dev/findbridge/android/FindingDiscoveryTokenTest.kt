package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class FindingDiscoveryTokenTest {
    private val sharedSecret = ByteArray(32) { (0xa0 + it).toByte() }

    @Test
    fun bucketDerivationMatchesCommonCryptoIncludingUnsignedIndices() {
        val vectors =
            listOf(
                0uL to "676ddfd2913140792d3aba1367bae06c",
                1uL to "cef76c13cdd42a9d15ab53ea5237b1d5",
                0x0102030405060708uL to "bdce99e67028319bb028607b5a90b9a1",
                ULong.MAX_VALUE to "a7e8cb0d5089f12f5d07ab9da7a04555",
            )
        for ((index, expected) in vectors) {
            assertArrayEquals(
                hex(expected),
                FindingCrypto.deriveBucketedDiscoverySecret(sharedSecret, index),
            )
        }
        assertFalse(
            FindingCrypto.deriveUnbucketedDiscoverySecret(sharedSecret)
                .contentEquals(FindingCrypto.deriveBucketedDiscoverySecret(sharedSecret, 0uL))
        )
    }

    @Test
    fun rawTokenMatchesTheHostFactoryAndOwnsItsKeyCopy() {
        val input = ByteArray(16) { it.toByte() }
        FindingDiscoveryToken.fromIrk(input).use { token ->
            input.fill(0)
            val expected = hex("e309731234560f090a80000102030405060708090a0b0c0d0e0f")
            assertArrayEquals(expected, token.rawToken())
            val exported = token.rawToken()
            exported.fill(0)
            assertArrayEquals(expected, token.rawToken())
            assertFalse(token.toString().contains("00010203"))
        }
    }

    @Test
    fun fixedIdentifierAdvertisementTagsMatchHostCoreUtils() {
        FindingDiscoveryToken.fromIrk(ByteArray(16) { it.toByte() }).use { token ->
            val address = hex("a0a1a2a3a4a5")
            assertArrayEquals(hex("06aba9"), token.spatialIdentifier(address))
            assertArrayEquals(hex("eabec2"), token.noWakeAuthTag(address))
            assertFalse(
                token.spatialIdentifier(address).contentEquals(token.noWakeAuthTag(address))
            )
            assertThrows(IllegalArgumentException::class.java) {
                token.spatialIdentifier(ByteArray(8))
            }
        }
    }

    @Test
    fun sharedSecretAndIrkLengthsStaySeparateAndClosedTokensCannotBeUsed() {
        for (length in listOf(0, 15, 16, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) {
                FindingDiscoveryToken.owner(ByteArray(length))
            }
        }
        val token = FindingDiscoveryToken.bucketed(sharedSecret, 1uL)
        token.close()
        token.close()
        assertThrows(IllegalStateException::class.java) { token.rawToken() }
        assertThrows(IllegalStateException::class.java) { token.spatialIdentifier(ByteArray(6)) }
        assertThrows(IllegalStateException::class.java) { token.noWakeAuthTag(ByteArray(6)) }
        val key =
            FindingDiscoveryToken::class
                .java
                .getDeclaredField("irk")
                .apply { isAccessible = true }
                .get(token) as ByteArray
        assertTrue(key.all { it == 0.toByte() })
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
