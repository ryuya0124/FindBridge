package dev.findbridge.android

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Pure synthetic crypto tests: no activity, account access, discovery or radio session. */
class FindingCryptoAndroidTest {
    private val irk = ByteArray(16) { it.toByte() }
    private val identifier = hex("1011121314151617")
    private val key = hex("7804b64218d58ff5d786486e71c480f61acbaf036ece66c672d9a976ad6178d2")
    private val plaintext = hex("01232021228141dcdfb57c45303132bdd034c197ec")
    private val envelope = hex("0104030201021c963dc5085c24222d231082e2986a192f598ab31ad8e16330d3")

    @Test
    fun nativeCcmAndKeyDerivationMatchMacFixtures() {
        assertArrayEquals(key, FindingCrypto.deriveSessionKey(irk, identifier))
        assertArrayEquals(envelope, FindingCrypto.seal(key, 0x01020304, plaintext))
        assertArrayEquals(plaintext, FindingCrypto.open(key, envelope))
        assertArrayEquals(
            hex("7a04aa48de2beabf511facb4071eeab8"),
            FindingCrypto.deriveUnbucketedDiscoverySecret(ByteArray(32) { (0xa0 + it).toByte() }),
        )
        assertArrayEquals(hex("643439df0fcfafdd"), FindingCrypto.addressForIrk(irk))
        assertArrayEquals(
            hex("8141dcdfb57c"),
            FindingCrypto.mutualAuthResponse(irk, hex("202122"), hex("303132")),
        )
    }

    @Test
    fun everyChangedByteWrongKeyAndWrongSessionIdentifierAreRejected() {
        for (index in envelope.indices) {
            val changed = envelope.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(GeneralSecurityException::class.java) { FindingCrypto.open(key, changed) }
        }
        val wrongKey = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) {
            FindingCrypto.open(wrongKey, envelope)
        }
        val wrongId = identifier.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) {
            FindingCrypto.open(FindingCrypto.deriveSessionKey(irk, wrongId), envelope)
        }
        for (length in 0..10) {
            assertThrows(GeneralSecurityException::class.java) {
                FindingCrypto.open(key, envelope.copyOf(length))
            }
        }
    }

    @Test
    fun unsignedCountersAndBlockBoundariesRoundTrip() {
        for (counter in listOf(0L, 1L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL)) {
            for (length in listOf(0, 1, 15, 16, 17, 507)) {
                val message = ByteArray(length) { it.toByte() }
                assertArrayEquals(
                    message,
                    FindingCrypto.open(key, FindingCrypto.seal(key, counter, message)),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FindingCrypto.seal(key, 0x1_0000_0000, plaintext)
        }
    }

    @Test
    fun encoderOwnsItsIdentifierAdvancesCounterAndDestroysItsKeyOnClose() {
        val random =
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    val data = if (bytes.size == 8) identifier else hex("03030201")
                    data.copyInto(bytes)
                }
            }
        val encoder = FindingMessageEncoder(irk, random)
        val returnedId = encoder.sessionIdentifier
        returnedId.fill(0)
        assertArrayEquals(identifier, encoder.sessionIdentifier)
        assertArrayEquals(envelope, encoder.seal(plaintext))
        val next = encoder.seal(plaintext)
        assertFalse(envelope.contentEquals(next))
        assertArrayEquals(plaintext, FindingCrypto.open(key, next))
        encoder.close()
        assertThrows(IllegalStateException::class.java) { encoder.seal(plaintext) }
        assertThrows(IllegalStateException::class.java) { encoder.sessionIdentifier }
        val retainedKey = encoder.javaClass.getDeclaredField("key").apply { isAccessible = true }
        assertArrayEquals(ByteArray(32), retainedKey.get(encoder) as ByteArray)
    }

    @Test
    fun exhaustedCounterRequiresANewSession() {
        FindingMessageEncoder(irk).use { encoder ->
            encoder.javaClass.getDeclaredField("counter").apply {
                isAccessible = true
                setLong(encoder, 0xffff_ffffL)
            }
            assertThrows(IllegalStateException::class.java) { encoder.seal(plaintext) }
        }
    }

    @Test
    fun platformCcmMatchesIndependentRfc3610Vector() {
        val cipher = Cipher.getInstance("AES/CCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(ByteArray(16) { (0xc0 + it).toByte() }, "AES"),
            GCMParameterSpec(64, hex("00000003020100a0a1a2a3a4a5")),
        )
        cipher.updateAAD(ByteArray(8) { it.toByte() })
        assertArrayEquals(
            hex("588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0"),
            cipher.doFinal(ByteArray(23) { (8 + it).toByte() }),
        )
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
