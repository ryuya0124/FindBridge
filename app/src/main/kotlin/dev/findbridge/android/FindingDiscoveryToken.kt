package dev.findbridge.android

/**
 * Finding variant 1 as observed in 24A446 / 26B123. No key acquisition or radio access. The
 * three-byte token identifier is distinct from the eight-byte CCM session identifier.
 */
internal class FindingDiscoveryToken private constructor(private val irk: ByteArray) :
    AutoCloseable {
    private var closed = false

    /** OPACK rawToken only; NSSecureCoding and the over-the-air advertisement are separate. */
    @Synchronized
    fun rawToken(): ByteArray {
        check(!closed)
        return byteArrayOf(0xe3.toByte(), 9, 0x73, 0x12, 0x34, 0x56, 15, 9, 10, 0x80.toByte()) + irk
    }

    /** Uses CoreBluetooth addressData byte order; Android MAC-string order is not assumed. */
    @Synchronized
    fun spatialIdentifier(addressData: ByteArray): ByteArray {
        check(!closed)
        require(addressData.size == 6)
        return FindingCrypto.sipHash24(irk, addressData + byteArrayOf(0x12, 0x34, 0x56))
            .reversedArray()
            .copyOf(3)
    }

    /** Only the address-based NearbyActionNoWake branch. Its other branch is not selected here. */
    @Synchronized
    fun noWakeAuthTag(addressData: ByteArray): ByteArray {
        check(!closed)
        require(addressData.size == 6)
        return FindingCrypto.sipHash24(irk, addressData).copyOf(3)
    }

    @Synchronized
    override fun close() {
        irk.fill(0)
        closed = true
    }

    override fun toString(): String = "FindingDiscoveryToken(variant=1, redacted)"

    companion object {
        fun fromIrk(irk: ByteArray): FindingDiscoveryToken {
            require(irk.size == 16)
            return FindingDiscoveryToken(irk.copyOf())
        }

        fun owner(sharedSecret: ByteArray): FindingDiscoveryToken =
            FindingDiscoveryToken(FindingCrypto.deriveUnbucketedDiscoverySecret(sharedSecret))

        fun bucketed(sharedSecret: ByteArray, index: ULong): FindingDiscoveryToken =
            FindingDiscoveryToken(FindingCrypto.deriveBucketedDiscoverySecret(sharedSecret, index))
    }
}
