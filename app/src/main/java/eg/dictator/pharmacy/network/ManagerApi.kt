package eg.dictator.pharmacy.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class PharmacyDevice(
    val id: String,
    val name: String,
    val role: String,
    val status: String,
    val createdAt: Long
)

data class PharmacyInvite(val code: String, val expiresAt: Long)

object ManagerApi {
    private const val BASE_URL = PharmacyApi.BASE_URL
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    fun createInvite(token: String): PharmacyInvite {
        val response = request("/v1/invites", "POST", token, JSONObject())
        return PharmacyInvite(
            code = response.optString("code"),
            expiresAt = response.optLong("expiresAt")
        ).also { if (it.code.isBlank()) throw IOException("لم يصل رمز الدعوة من الخادم") }
    }

    fun listDevices(token: String): List<PharmacyDevice> {
        val response = request("/v1/manager/devices", "GET", token, null)
        val rows = response.optJSONArray("devices") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            PharmacyDevice(
                id = row.optString("id"),
                name = row.optString("name"),
                role = row.optString("role"),
                status = row.optString("status"),
                createdAt = row.optLong("created_at")
            )
        }
    }

    fun approveDevice(token: String, deviceId: String): String {
        val response = request("/v1/manager/devices/$deviceId/approve", "POST", token, JSONObject())
        return response.optString("token").takeIf { it.length >= 32 }
            ?: throw IOException("لم يصل رمز الجهاز بعد الموافقة")
    }

    fun revokeDevice(token: String, deviceId: String) {
        request("/v1/manager/devices/$deviceId/revoke", "POST", token, JSONObject())
    }

    private fun request(path: String, method: String, token: String, payload: JSONObject?): JSONObject {
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = try { JSONObject(text) } catch (_: Exception) {
                throw IOException("استجابة غير مفهومة من الخادم")
            }
            if (status !in 200..299) {
                val code = result.optString("error", "server_error")
                val message = when (code) {
                    "manager_auth_required" -> "هذه العملية متاحة لمدير الصيدلية فقط"
                    "pending_device_not_found" -> "الجهاز غير موجود أو تمت الموافقة عليه بالفعل"
                    "device_not_found" -> "الجهاز غير موجود"
                    else -> "تعذر تنفيذ العملية: $code"
                }
                throw IOException(message)
            }
            return result
        } finally {
            connection.disconnect()
        }
    }
}
