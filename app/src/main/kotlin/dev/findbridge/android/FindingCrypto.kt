package dev.findbridge.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto primitives observed in 24A446 nearbyd. This does not acquire owner tokens, discover a
 * peer, implement NBAMMS, or start a radio session. See docs/UWB-24A446.md.
 */
internal object FindingCrypto {
    private val addressInput =
        byteArrayOf(
            1,
            0x23,
            0x45,
            0x67,
            0x89.toByte(),
            0xab.toByte(),
            0xcd.toByte(),
            0xef.toByte(),
        )

    fun deriveSessionKey(irk: ByteArray, sessionIdentifier: ByteArray): ByteArray {
        require(irk.size == 16)
        require(sessionIdentifier.size == 8)
        return hkdf(irk, byteArrayOf(), sessionIdentifier, 32)
    }

    /** The owner-token branch uses an empty info field; it does not use a rotation index. */
    fun deriveUnbucketedDiscoverySecret(sharedSecret: ByteArray): ByteArray {
        require(sharedSecret.size == 32)
        return hkdf(
            sharedSecret,
            "NITokenService.salt".toByteArray(Charsets.UTF_8),
            byteArrayOf(),
            16,
        )
    }

    /** Verified UInt64.data encoding: eight little-endian bytes, including the high bit. */
    fun deriveBucketedDiscoverySecret(sharedSecret: ByteArray, index: ULong): ByteArray {
        require(sharedSecret.size == 32)
        return hkdf(
            sharedSecret,
            "NITokenService.salt".toByteArray(Charsets.UTF_8),
            littleEndian(index.toLong()),
            16,
        )
    }

    fun addressForIrk(irk: ByteArray): ByteArray = sipHash24(irk, addressInput)

    /** The IRK belongs to the role token, which need not be the session token. */
    fun mutualAuthResponse(irk: ByteArray, outbound: ByteArray, inbound: ByteArray): ByteArray {
        require(outbound.size == 3 && inbound.size == 3)
        return sipHash24(irk, outbound + inbound).copyOf(6)
    }

    /** Stateless format reproduction. A sender must never reuse a counter for the same key. */
    fun seal(key: ByteArray, counter: Long, plaintext: ByteArray): ByteArray {
        require(key.size == 32)
        require(counter in 0..0xffff_ffffL)
        val header = byteArrayOf(1) + littleEndian(counter).copyOf(4)
        val cipher = Cipher.getInstance("AES/CCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(48, littleEndian(counter)),
        )
        cipher.updateAAD(header)
        val encrypted = cipher.doFinal(plaintext) // JCA returns ciphertext || tag.
        return header +
            encrypted.copyOfRange(encrypted.size - 6, encrypted.size) +
            encrypted.copyOfRange(0, encrypted.size - 6)
    }

    /** Returns plaintext only after the provider verifies CCM authentication. */
    fun open(key: ByteArray, envelope: ByteArray): ByteArray {
        require(key.size == 32)
        if (envelope.size < 11 || envelope[0] != 1.toByte()) {
            throw GeneralSecurityException("Invalid finding message")
        }
        val counter =
            ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and
                0xffff_ffffL
        val cipher = Cipher.getInstance("AES/CCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(48, littleEndian(counter)),
        )
        cipher.updateAAD(envelope, 0, 5)
        return cipher.doFinal(envelope.copyOfRange(11, envelope.size) + envelope.copyOfRange(5, 11))
    }

    fun sipHash24(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == 16)
        val keys = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        val k0 = keys.long
        val k1 = keys.long
        val state =
            longArrayOf(
                0x736f6d6570736575L xor k0,
                0x646f72616e646f6dL xor k1,
                0x6c7967656e657261L xor k0,
                0x7465646279746573L xor k1,
            )
        val end = message.size - message.size % 8
        val words = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN)
        while (words.position() < end) {
            val word = words.long
            state[3] = state[3] xor word
            rounds(state, 2)
            state[0] = state[0] xor word
        }
        var tail = (message.size.toLong() and 255) shl 56
        for (i in end until message.size) {
            tail = tail or ((message[i].toLong() and 255) shl ((i - end) * 8))
        }
        state[3] = state[3] xor tail
        rounds(state, 2)
        state[0] = state[0] xor tail
        state[2] = state[2] xor 255
        rounds(state, 4)
        val result = littleEndian(state[0] xor state[1] xor state[2] xor state[3])
        state.fill(0)
        return result
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        try {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val expanded = mac.doFinal(info + byteArrayOf(1))
            return expanded.copyOf(length).also { expanded.fill(0) }
        } finally {
            prk.fill(0)
        }
    }

    private fun littleEndian(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun rounds(v: LongArray, count: Int) {
        repeat(count) {
            v[0] += v[1]
            v[1] = java.lang.Long.rotateLeft(v[1], 13) xor v[0]
            v[0] = java.lang.Long.rotateLeft(v[0], 32)
            v[2] += v[3]
            v[3] = java.lang.Long.rotateLeft(v[3], 16) xor v[2]
            v[0] += v[3]
            v[3] = java.lang.Long.rotateLeft(v[3], 21) xor v[0]
            v[2] += v[1]
            v[1] = java.lang.Long.rotateLeft(v[1], 17) xor v[2]
            v[2] = java.lang.Long.rotateLeft(v[2], 32)
        }
    }
}

/** Owns a fresh direction-specific ID and advances counters without polling or nonce reuse. */
internal class FindingMessageEncoder(irk: ByteArray, random: SecureRandom = SecureRandom()) :
    AutoCloseable {
    private val identifier = ByteArray(8).also(random::nextBytes)
    private val key = FindingCrypto.deriveSessionKey(irk, identifier)
    private var counter = initialCounter(random)
    private var closed = false

    @get:Synchronized
    val sessionIdentifier: ByteArray
        get() {
            check(!closed) { "Finding crypto session is closed" }
            return identifier.copyOf()
        }

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        check(!closed) { "Finding crypto session is closed" }
        check(counter < 0xffff_ffffL) { "Finding crypto session must be renewed" }
        counter += 1 // Consume the nonce even when encryption fails.
        return FindingCrypto.seal(key, counter, plaintext)
    }

    @Synchronized
    override fun close() {
        key.fill(0)
        identifier.fill(0)
        closed = true
    }

    private fun initialCounter(random: SecureRandom): Long {
        val bytes = ByteArray(4)
        while (true) {
            random.nextBytes(bytes)
            val value =
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
            if (value < 0xffff_0000L) return value
        }
    }
}
