package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class FindingAdvertisementTest {
    @Test
    fun completeManufacturerFramesMatchTheHostCoreBluetoothParser() {
        // Fixed packets passed through 26B123 CBDevice's in-memory parser.
        val fixtures =
            listOf(
                "13094906aba90117025a00" to null,
                "130bc906aba90117025a00017f" to 0x7f,
                "13e94906aba90117025a00" to null,
            )
        for ((frame, presence) in fixtures) {
            val spatial = parse(frame).single() as FindingAdvertisement.Spatial
            assertArrayEquals(hex("06aba9"), spatial.identifiers.single())
            assertArrayEquals(hex("0117025a00"), spatial.uwbConfig)
            assertEquals(presence, spatial.presence)
        }
        val multiple =
            parse("130c4a06aba9aabbcc0117025a00").single() as FindingAdvertisement.Spatial
        assertEquals(2, multiple.identifiers.size)
        assertArrayEquals(hex("aabbcc"), multiple.identifiers[1])
        assertArrayEquals(hex("0117025a00"), multiple.uwbConfig)
    }

    @Test
    fun presenceOnlyDoesNotSupplyRangingConfiguration() {
        val spatial = parse("13068106aba9017f").single() as FindingAdvertisement.Spatial
        assertNull(spatial.uwbConfig)
        assertEquals(1, spatial.presenceFlags)
        assertEquals(0x7f, spatial.presence)
    }

    @Test
    fun unknownTypesAreSkippedWithoutConfusingSpatialAndNoWakeTags() {
        val result = parse("ff01aa13094906aba90117025a001a060101eabec27f")
        assertEquals(2, result.size)
        val noWake = result[1] as FindingAdvertisement.NoWake
        assertArrayEquals(hex("eabec2"), noWake.authTag)
        assertEquals(0x7f, noWake.config)
        assertTrue(parse("1c094906aba90117025a00").isEmpty())
        assertTrue(parse("1a0102").isEmpty()) // Unknown NoWake version.
        assertTrue(FindingAdvertisementParser.parse(0x0006, hex("1300")).isEmpty())
    }

    @Test
    fun truncationCannotBecomeAPartiallyValidCandidate() {
        for (frame in
            listOf("13094906aba90117025a00", "130bc906aba90117025a00017f", "1a060101eabec27f")) {
            val bytes = hex(frame)
            for (size in 1 until bytes.size) {
                assertThrows(IllegalArgumentException::class.java) {
                    FindingAdvertisementParser.parse(0x004c, bytes.copyOf(size))
                }
            }
            // Also reject a valid TLV header around each truncated known body.
            val body = bytes.copyOfRange(2, bytes.size)
            for (size in body.indices) {
                assertThrows(IllegalArgumentException::class.java) {
                    FindingAdvertisementParser.parse(
                        0x004c,
                        byteArrayOf(bytes[0], size.toByte()) + body.copyOf(size),
                    )
                }
            }
        }
    }

    @Test
    fun finderAndFindeeHaveDifferentProtocolAndAcquisitionFields() {
        val findee = FindingRadioAdvertisement.decode(hex("0117025a00"), false)
        assertTrue(findee.findingEnabled && findee.canRange && findee.technology1Advertised)
        assertEquals(1, findee.useCaseCode)
        assertEquals(2, findee.protocolVersion)
        assertEquals(3, findee.narrowbandMaskCode)
        assertEquals(2, findee.mmsFragmentCode)
        assertEquals(2, findee.acquisitionChannelIndex)
        val finder = FindingRadioAdvertisement.decode(hex("01175a0000"), true)
        assertEquals(findee.protocolVersion, finder.protocolVersion)
        assertEquals(findee.narrowbandMaskCode, finder.narrowbandMaskCode)
        assertNull(finder.acquisitionChannelIndex)
    }

    @Test
    fun disabledOrMissingProtocolDoesNotInventSessionParameters() {
        val disabled = FindingRadioAdvertisement.decode(hex("00ffffffff"), false)
        assertFalse(disabled.findingEnabled || disabled.canRange || disabled.technology1Advertised)
        assertNull(disabled.protocolVersion)
        assertNull(disabled.acquisitionChannelIndex)
        val absent = FindingRadioAdvertisement.decode(hex("01065a5a00"), false)
        assertEquals(0, absent.protocolVersion)
        assertNull(absent.narrowbandMaskCode)
        assertNull(absent.acquisitionChannelIndex)
        assertThrows(IllegalArgumentException::class.java) {
            FindingRadioAdvertisement.decode(ByteArray(6), false)
        }
    }

    private fun parse(frame: String) = FindingAdvertisementParser.parse(0x004c, hex(frame))

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
