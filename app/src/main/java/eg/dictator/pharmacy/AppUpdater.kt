package eg.dictator.pharmacy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(
    val tag: String,
    val name: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?
)

object AppUpdater {
    private const val LATEST_RELEASE = "https://api.github.com/repos/DictatorEG2/Dictator-Pharmacy/releases/latest"

    fun checkForUpdate(): AppRelease? {
        val connection = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Dictator-Pharmacy-Android")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("تعذر فحص التحديثات (HTTP ${connection.responseCode}).")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
            if (tag.isBlank()) throw IllegalStateException("بيانات الإصدار غير مكتملة.")
            val current = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V")
            if (compareVersions(tag, current) <= 0) return null

            val assets = json.optJSONArray("assets") ?: throw IllegalStateException("الإصدار لا يحتوي على ملفات تنزيل.")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val fileName = asset.optString("name")
                if (fileName.endsWith(".apk", ignoreCase = true)) {
                    return AppRelease(
                        tag = tag,
                        name = json.optString("name", "الإصدار $tag"),
                        apkUrl = asset.getString("browser_download_url"),
                        apkSize = asset.optLong("size"),
                        sha256 = asset.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
                    )
                }
            }
            throw IllegalStateException("الإصدار الجديد منشور لكن ملف APK غير مرفق به.")
        } finally {
            connection.disconnect()
        }
    }

    fun downloadRelease(context: Context, release: AppRelease): File {
        val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("مساحة التنزيل غير متاحة.")
        if (!directory.exists()) directory.mkdirs()
        val apk = File(directory, "dictator-pharmacy-${release.tag}.apk")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Dictator-Pharmacy-Android")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("فشل تنزيل التحديث (HTTP ${connection.responseCode}).")
            }
            val length = connection.contentLengthLong
            if (length > 200L * 1024L * 1024L) throw IllegalStateException("حجم ملف التحديث أكبر من الحد المسموح.")
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 200L * 1024L * 1024L) throw IllegalStateException("ملف التحديث أكبر من الحد المسموح.")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (apk.length() < 4L || apk.inputStream().use { it.read() } != 0x50) {
                apk.delete()
                throw IllegalStateException("الملف الذي تم تنزيله ليس حزمة APK صالحة.")
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !actualHash.equals(release.sha256, ignoreCase = true)) {
                apk.delete()
                throw IllegalStateException("فشل التحقق من سلامة ملف التحديث.")
            }
            return apk
        } catch (error: Exception) {
            apk.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun installOrRequestPermission(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(install)
        return true
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
