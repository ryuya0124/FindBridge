package dev.findbridge.android

/** Parsed broadcast fields only. A 24-bit tag match is not ownership authentication. */
internal sealed interface FindingAdvertisement {
    class Spatial(
        val flags: Int,
        val identifiers: List<ByteArray>,
        val uwbConfig: ByteArray?,
        val presenceFlags: Int?,
        val presence: Int?,
    ) : FindingAdvertisement

    class NoWake(val flags: Int, val authTag: ByteArray, val config: Int?) : FindingAdvertisement
}

/**
 * Manufacturer data as returned by ScanRecord.getManufacturerSpecificData(0x004c): the company ID
 * and BLE AD header have already been removed. Verified against 26B123 CBDevice using fixed
 * synthetic packets. No scanning or radio access.
 */
internal object FindingAdvertisementParser {
    fun parse(companyId: Int, data: ByteArray): List<FindingAdvertisement> {
        if (companyId != 0x004c) return emptyList()
        val cursor = Cursor(data)
        val result = mutableListOf<FindingAdvertisement>()
        while (cursor.remaining > 0) {
            val type = cursor.byte()
            // CBDevice masks the upper three header bits before advancing.
            val body = cursor.take(cursor.byte() and 0x1f)
            when (type) {
                0x13 -> result.add(spatial(body))
                0x1a -> noWake(body)?.let(result::add)
            }
        }
        return result
    }

    private fun spatial(body: ByteArray): FindingAdvertisement.Spatial {
        val cursor = Cursor(body)
        val flags = cursor.byte()
        val identifiers = List(flags and 3) { cursor.take(3) }
        val extended = flags and 0x80 != 0
        val uwb =
            if (flags and 8 != 0 && (!extended || flags and 0x40 != 0)) cursor.take(5) else null
        val presenceFlags = if (extended) cursor.byte() else null
        val presence =
            if (presenceFlags != null && presenceFlags and 1 != 0) cursor.byte() else null
        // Unknown trailing fields are not interpreted as session parameters.
        return FindingAdvertisement.Spatial(flags, identifiers, uwb, presenceFlags, presence)
    }

    private fun noWake(body: ByteArray): FindingAdvertisement.NoWake? {
        val cursor = Cursor(body)
        if (cursor.byte() != 1) return null // Only the observed version is implemented.
        val flags = cursor.byte()
        val tag = cursor.take(3)
        val config = if (flags and 1 != 0) cursor.byte() else null
        return FindingAdvertisement.NoWake(flags, tag, config)
    }

    /** Rejects truncated packets instead of retaining Apple's partial-error state. */
    private class Cursor(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int
            get() = bytes.size - offset

        fun byte(): Int {
            require(remaining >= 1) { "Truncated finding advertisement" }
            return bytes[offset++].toInt() and 255
        }

        fun take(length: Int): ByteArray {
            require(length >= 0 && length <= remaining) { "Truncated finding advertisement" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
    }
}
