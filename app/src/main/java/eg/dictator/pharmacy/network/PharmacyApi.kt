package eg.dictator.pharmacy.network

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class PharmacyRegistration(
    val pharmacyId: String,
    val pharmacyName: String,
    val deviceId: String,
    val token: String,
    val role: String
)

/**
 * Minimal HTTPS client for the deployed Cloudflare Worker.
 * Call from Dispatchers.IO; this class never persists credentials.
 */
object PharmacyApi {
    const val BASE_URL = "https://dictator-pharmacy.dictatoreg.workers.dev"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    @Throws(IOException::class)
    fun registerPharmacy(pharmacyName: String, deviceName: String): PharmacyRegistration {
        require(pharmacyName.isNotBlank()) { "اكتب اسم الصيدلية" }
        require(deviceName.isNotBlank()) { "اكتب اسم الجهاز" }

        val payload = JSONObject()
            .put("pharmacyName", pharmacyName.trim())
            .put("deviceName", deviceName.trim())
            .toString()

        val connection = (URL("$BASE_URL/v1/pharmacies").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                throw IOException("استجابة غير مفهومة من الخادم")
            }

            if (status !in 200..299) {
                val code = response.optString("error", "server_error")
                val message = when (code) {
                    "registration_rate_limited" -> "تم تجاوز عدد محاولات التسجيل. حاول لاحقًا."
                    "registration_security_not_configured" -> "تسجيل الصيدليات متوقف مؤقتًا لإعدادات الحماية."
                    else -> "تعذر إنشاء الصيدلية: $code"
                }
                throw IOException(message)
            }

            val result = PharmacyRegistration(
                pharmacyId = response.optString("pharmacyId"),
                pharmacyName = response.optString("pharmacyName"),
                deviceId = response.optString("deviceId"),
                token = response.optString("token"),
                role = response.optString("role")
            )
            if (result.pharmacyId.isBlank() || result.deviceId.isBlank() ||
                result.token.length < 32 || result.role != "manager"
            ) {
                throw IOException("بيانات تسجيل الصيدلية غير مكتملة")
            }
            return result
        } finally {
            connection.disconnect()
        }
    }
}
