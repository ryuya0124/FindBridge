package dev.findbridge.android.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/** Hash and signing certificates are checked on a private copy, never a mutable shared APK. */
internal class UpdateApkVerifier(private val context: Context) {
    fun verify(file: File, release: UpdateRelease) {
        check(file.isFile && file.length() == release.size)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val archive =
            manager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: error("Invalid APK")
        @Suppress("DEPRECATION")
        val installed =
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(archive.packageName == context.packageName)
        check(archive.longVersionCode > installed.longVersionCode)
        check(
            ReleaseVersion.parse(release.version)!! >
                (ReleaseVersion.parse(installed.versionName.orEmpty())
                    ?: error("Invalid installed version"))
        )
        check(archive.versionName == release.version)
        check(
            (archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <=
                android.os.Build.VERSION.SDK_INT
        )
        val incoming =
            archive.signingInfo
                ?.apkContentsSigners
                ?.map { it.toByteArray().toList() }
                ?.toSet()
                .orEmpty()
        val existing =
            installed.signingInfo
                ?.apkContentsSigners
                ?.map { it.toByteArray().toList() }
                ?.toSet()
                .orEmpty()
        check(incoming.isNotEmpty() && incoming == existing)
    }
}
