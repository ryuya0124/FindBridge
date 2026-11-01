package dev.findbridge.android.update

/** Release tags are compared numerically, including rc9 -> rc10 and preview -> stable. */
internal data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val stage: Int,
    val sequence: Int,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(
            this,
            other,
            { it.major },
            { it.minor },
            { it.patch },
            { it.stage },
            { it.sequence },
        )

    val preview
        get() = stage < 3

    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match =
                Regex(
                        "v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)([1-9][0-9]*))?"
                    )
                    .matchEntire(value) ?: return null
            val parts = match.groupValues
            return runCatching {
                ReleaseVersion(
                    parts[1].toInt(),
                    parts[2].toInt(),
                    parts[3].toInt(),
                    when (parts[4]) {
                        "alpha" -> 0
                        "beta" -> 1
                        "rc" -> 2
                        else -> 3
                    },
                    parts[5].ifEmpty { "0" }.toInt(),
                )
            }
                .getOrNull()
        }
    }
}
