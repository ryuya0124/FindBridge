package dev.findbridge.android.update

import org.json.JSONArray
import org.json.JSONObject

internal data class UpdateRelease(
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val notes: String,
) {
    fun json() =
        JSONObject()
            .put("version", version)
            .put("url", url)
            .put("sha256", sha256)
            .put("size", size)
            .put("notes", notes)
            .toString()

    companion object {
        const val MAX_APK_SIZE = 50L * 1024 * 1024
        private const val ROOT = "https://github.com/ryuya0124/FindBridge/releases/download/"

        fun decode(json: String): UpdateRelease? = runCatching {
            val value = JSONObject(json)
            UpdateRelease(
                    value.getString("version"),
                    value.getString("url"),
                    value.getString("sha256"),
                    value.getLong("size"),
                    value.optString("notes").take(12_000),
                )
                .takeIf { it.valid() }
        }
            .getOrNull()

        fun latest(json: String, installed: String, previews: Boolean): UpdateRelease? {
            val current = ReleaseVersion.parse(installed) ?: return null
            val releases = JSONArray(json)
            return (0 until releases.length())
                .mapNotNull { index ->
                    runCatching {
                        val release = releases.getJSONObject(index)
                        if (release.optBoolean("draft")) return@runCatching null
                        val tag = release.getString("tag_name")
                        val version = ReleaseVersion.parse(tag) ?: return@runCatching null
                        if (
                            version <= current ||
                                (!previews && (version.preview || release.optBoolean("prerelease")))
                        )
                            return@runCatching null
                        val name = tag.removePrefix("v")
                        val assets = release.getJSONArray("assets")
                        (0 until assets.length()).firstNotNullOfOrNull { assetIndex ->
                            val asset = assets.getJSONObject(assetIndex)
                            if (
                                asset.optString("name") != "FindBridge-$name-arm64-release.apk" ||
                                    asset.optString("state") != "uploaded"
                            )
                                null
                            else
                                UpdateRelease(
                                        name,
                                        asset.getString("browser_download_url"),
                                        asset.optString("digest").removePrefix("sha256:"),
                                        asset.getLong("size"),
                                        release.optString("body").take(12_000),
                                    )
                                    .takeIf {
                                        asset.optString("digest").startsWith("sha256:") &&
                                            it.valid()
                                    }
                        }
                    }
                        .getOrNull()
                }
                .maxByOrNull { ReleaseVersion.parse(it.version)!! }
        }

        private fun UpdateRelease.valid(): Boolean =
            ReleaseVersion.parse(version) != null &&
                url == "${ROOT}v$version/FindBridge-$version-arm64-release.apk" &&
                sha256.matches(Regex("[a-f0-9]{64}")) &&
                size in 1..MAX_APK_SIZE
    }
}
