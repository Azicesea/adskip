package com.dafeiyu.adskip

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class GitHubUpdates(private val context: Context) {
    data class Release(
        val versionCode: Long,
        val tag: String,
        val notes: String,
        val apkUrl: URL,
        val apkSize: Long
    )

    data class Downloaded(val versionCode: Long, val versionName: String, val file: File)

    class Failure(@StringRes val messageRes: Int) : IOException()

    private val updateDir = File(context.cacheDir, "updates")
    private val apkFile = File(updateDir, "update.apk")
    private val partialFile = File(updateDir, "update-partial.apk")

    fun latest(): Release? {
        val release = try {
            readJson(URL("https://api.github.com/repos/$REPOSITORY/releases/latest"), 512 * 1024L)
        } catch (failure: HttpFailure) {
            if (failure.status == 404) throw Failure(R.string.update_no_release)
            throw failure
        }
        val assets = release.getJSONArray("assets")
        val metadata = findAsset(assets, "update.json") ?: throw Failure(R.string.update_no_assets)
        val versionCode = when (val code = readJson(assetUrl(metadata), 8 * 1024L).opt("versionCode")) {
            is Int -> code.toLong()
            is Long -> code
            else -> throw IOException("Invalid update version code")
        }
        if (versionCode <= 0) throw IOException("Invalid update version code")
        if (versionCode <= currentVersion()) return null

        val apk = findAsset(assets, "adskip.apk") ?: throw Failure(R.string.update_no_assets)
        val apkSize = apk.getLong("size")
        if (apkSize !in 1..MAX_APK_BYTES) throw IOException("Invalid APK size")
        return Release(
            versionCode,
            release.getString("tag_name"),
            (release.opt("body") as? String).orEmpty().trim().take(4000),
            assetUrl(apk),
            apkSize
        )
    }

    fun cached(): Downloaded? {
        partialFile.delete()
        if (!apkFile.isFile) return null
        return try {
            verifyApk(apkFile)
        } catch (_: Failure) {
            apkFile.delete()
            null
        }
    }

    fun download(release: Release): Downloaded {
        if (!updateDir.isDirectory && !updateDir.mkdirs()) {
            throw IOException("Cannot create update cache")
        }
        try {
            request(release.apkUrl, "application/octet-stream") { input ->
                val size = partialFile.outputStream().use { output ->
                    copyLimited(input, output, release.apkSize)
                }
                if (size != release.apkSize) throw IOException("Incomplete APK")
            }
            val downloaded = verifyApk(partialFile, release.versionCode)
            if (!partialFile.renameTo(apkFile)) throw IOException("Cannot store verified APK")
            return downloaded.copy(file = apkFile)
        } finally {
            partialFile.delete()
        }
    }

    private fun findAsset(assets: JSONArray, name: String): JSONObject? {
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == name && asset.optString("state") == "uploaded") return asset
        }
        return null
    }

    private fun assetUrl(asset: JSONObject): URL {
        val url = URL(asset.getString("browser_download_url"))
        if (url.protocol != "https" || url.host != "github.com" ||
            !url.path.startsWith("/$REPOSITORY/releases/download/")
        ) throw IOException("Unexpected release asset URL")
        return url
    }

    private fun readJson(url: URL, maxBytes: Long): JSONObject =
        request(url, "application/vnd.github+json") { input ->
            val output = ByteArrayOutputStream()
            copyLimited(input, output, maxBytes)
            JSONObject(output.toString(Charsets.UTF_8.name()))
        }

    private fun <T> request(url: URL, accept: String, read: (InputStream) -> T): T {
        var nextUrl = url
        repeat(6) {
            val host = nextUrl.host
            if (nextUrl.protocol != "https" || nextUrl.userInfo != null ||
                (nextUrl.port != -1 && nextUrl.port != 443) ||
                (host != "github.com" && host != "api.github.com" &&
                    !host.endsWith(".githubusercontent.com"))
            ) throw IOException("Unexpected update URL")

            val connection = (nextUrl.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "AdSkip")
                setRequestProperty("Accept", accept)
            }
            try {
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use(read)
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Missing redirect URL")
                        nextUrl = URL(nextUrl, location)
                    }
                    else -> throw HttpFailure(status)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return total
            total += count
            if (total > limit) throw IOException("Update file exceeds the expected size")
            output.write(buffer, 0, count)
        }
    }

    private fun currentVersion(): Long = PackageInfoCompat.getLongVersionCode(
        context.packageManager.getPackageInfo(context.packageName, 0)
    )

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, expectedVersion: Long? = null): Downloaded {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw Failure(R.string.update_invalid_apk)
        val installed = manager.getPackageInfo(context.packageName, flags)
        val version = PackageInfoCompat.getLongVersionCode(archive)
        if (archive.packageName != installed.packageName ||
            version <= PackageInfoCompat.getLongVersionCode(installed) ||
            (expectedVersion != null && version != expectedVersion)
        ) throw Failure(R.string.update_invalid_apk)
        if (!matchesSigningCertificate(installed, archive)) {
            throw Failure(R.string.update_signature_mismatch)
        }
        return Downloaded(version, archive.versionName ?: version.toString(), file)
    }

    @Suppress("DEPRECATION")
    private fun matchesSigningCertificate(installed: PackageInfo, archive: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val signers = installed.signatures.orEmpty().toSet()
            return signers.isNotEmpty() && signers == archive.signatures.orEmpty().toSet()
        }
        val current = installed.signingInfo ?: return false
        val candidate = archive.signingInfo ?: return false
        val currentSigners = current.apkContentsSigners.orEmpty().toSet()
        val candidateSigners = candidate.apkContentsSigners.orEmpty().toSet()
        return (currentSigners.isNotEmpty() && currentSigners == candidateSigners) ||
            (currentSigners.size == 1 && candidateSigners.size == 1 &&
                candidate.signingCertificateHistory.orEmpty().contains(currentSigners.single()))
    }

    private class HttpFailure(val status: Int) : IOException("HTTP $status")

    private companion object {
        const val REPOSITORY = "Azicesea/adskip"
        const val MAX_APK_BYTES = 128 * 1024 * 1024L
    }
}
