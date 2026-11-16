package dev.findbridge.android

/** NBAMMS advertisement codes, not Android FiRa configuration IDs or channel numbers. */
internal data class FindingRadioAdvertisement(
    val findingEnabled: Boolean,
    val canRange: Boolean,
    val technology1Advertised: Boolean,
    val useCaseCode: Int?,
    val protocolVersion: Int?,
    val narrowbandMaskCode: Int?,
    val mmsFragmentCode: Int?,
    val acquisitionChannelIndex: Int?,
) {
    companion object {
        fun decode(uwbConfig: ByteArray, senderIsFinder: Boolean): FindingRadioAdvertisement {
            require(uwbConfig.size == 5)
            val enabled = uwbConfig[0].toInt() and 1 != 0
            val status = if (enabled) uwbConfig[1].toInt() and 255 else 0
            val hasProtocol = enabled && status and 1 != 0
            val packed = uwbConfig[if (senderIsFinder) 2 else 3].toInt() and 255
            return FindingRadioAdvertisement(
                findingEnabled = enabled,
                canRange = status and 2 != 0,
                technology1Advertised = status and 4 != 0,
                useCaseCode = if (enabled) status shr 4 and 1 else null,
                protocolVersion = if (enabled) (if (hasProtocol) packed and 7 else 0) else null,
                narrowbandMaskCode = if (hasProtocol) packed shr 3 and 3 else null,
                mmsFragmentCode = if (hasProtocol) packed shr 5 and 3 else null,
                acquisitionChannelIndex =
                    if (hasProtocol && !senderIsFinder) uwbConfig[2].toInt() and 255 else null,
            )
        }
    }
}
