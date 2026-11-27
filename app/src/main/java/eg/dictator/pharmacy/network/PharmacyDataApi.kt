package eg.dictator.pharmacy.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RemoteMedicine(
    val id: String, val name: String, val category: String?, val barcode: String?,
    val unit: String, val salePriceMinor: Long, val lowStockThreshold: Int
)

data class RemoteCustomer(
    val id: String, val name: String, val phone: String?, val notes: String?
)

data class RemoteSale(
    val id: String, val invoiceNumber: String?, val status: String,
    val totalMinor: Long, val paidMinor: Long, val dueMinor: Long,
    val createdAt: Long, val customerName: String?
)

data class RemoteReport(
    val saleCount: Int, val revenueMinor: Long, val paidMinor: Long,
    val dueMinor: Long
)

/**
 * Tenant-scoped pharmacy data API. All calls require the current device token.
 * Invoke from Dispatchers.IO. Monetary values are integer minor units (piastres).
 */
object PharmacyDataApi {
    private const val BASE_URL = PharmacyApi.BASE_URL

    fun listMedicines(token: String, query: String = "", page: Int = 1): List<RemoteMedicine> {
        val path = "/v1/medicines?q=${encode(query)}&page=${page.coerceAtLeast(1)}"
        val rows = request(path, "GET", token, null).optJSONArray("medicines") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            RemoteMedicine(
                id = row.optString("id"), name = row.optString("name"),
                category = row.optString("category").takeIf { it.isNotBlank() && it != "null" },
                barcode = row.optString("barcode").takeIf { it.isNotBlank() && it != "null" },
                unit = row.optString("unit", "piece"),
                salePriceMinor = row.optLong("sale_price_minor"),
                lowStockThreshold = row.optInt("low_stock_threshold")
            )
        }
    }

    fun createMedicine(
        token: String, name: String, category: String?, barcode: String?,
        unit: String, salePriceMinor: Long, lowStockThreshold: Int
    ): String {
        val body = JSONObject().put("name", name).put("unit", unit)
            .put("salePriceMinor", salePriceMinor).put("lowStockThreshold", lowStockThreshold)
        category?.let { body.put("category", it) }
        barcode?.let { body.put("barcode", it) }
        return request("/v1/medicines", "POST", token, body).optString("id")
            .takeIf { it.isNotBlank() } ?: throw IOException("لم يصل معرّف الدواء من الخادم")
    }

    fun receiveStock(
        token: String, medicineId: String, quantity: Int,
        batchNumber: String? = null, expiryDate: String? = null,
        purchasePriceMinor: Long = 0
    ): String {
        val body = JSONObject().put("medicineId", medicineId).put("quantity", quantity)
            .put("purchasePriceMinor", purchasePriceMinor)
        batchNumber?.let { body.put("batchNumber", it) }
        expiryDate?.let { body.put("expiryDate", it) }
        return request("/v1/inventory/batches", "POST", token, body).optString("id")
            .takeIf { it.isNotBlank() } ?: throw IOException("لم يصل معرّف دفعة المخزون")
    }

    fun createSale(
        token: String, operationId: String, items: List<Pair<String, Int>>,
        customerId: String? = null, paymentAmountMinor: Long = 0,
        paymentMethod: String = "cash"
    ): JSONObject {
        val rows = JSONArray()
        items.forEach { (medicineId, quantity) ->
            rows.put(JSONObject().put("medicineId", medicineId).put("quantity", quantity))
        }
        val body = JSONObject().put("operationId", operationId).put("items", rows)
            .put("paymentAmountMinor", paymentAmountMinor).put("paymentMethod", paymentMethod)
        customerId?.let { body.put("customerId", it) }
        return request("/v1/sales", "POST", token, body)
    }

    fun listCustomers(token: String, query: String = "", page: Int = 1): List<RemoteCustomer> {
        val path = "/v1/customers?q=${encode(query)}&page=${page.coerceAtLeast(1)}"
        val rows = request(path, "GET", token, null).optJSONArray("customers") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            RemoteCustomer(
                id = row.optString("id"), name = row.optString("name"),
                phone = row.optString("phone").takeIf { it.isNotBlank() && it != "null" },
                notes = row.optString("notes").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    fun createCustomer(token: String, name: String, phone: String?, notes: String?): String {
        val body = JSONObject().put("name", name)
        phone?.let { body.put("phone", it) }
        notes?.let { body.put("notes", it) }
        return request("/v1/customers", "POST", token, body).optString("id")
            .takeIf { it.isNotBlank() } ?: throw IOException("لم يصل معرّف العميل")
    }

    fun addSalePayment(
        token: String, saleId: String, amountMinor: Long,
        paymentMethod: String = "cash", notes: String? = null
    ) {
        val body = JSONObject().put("amountMinor", amountMinor).put("paymentMethod", paymentMethod)
        notes?.let { body.put("notes", it) }
        request("/v1/sales/$saleId/payments", "POST", token, body)
    }

    fun getSales(token: String, page: Int = 1): List<RemoteSale> {
        val rows = request("/v1/sales?page=${page.coerceAtLeast(1)}", "GET", token, null)
            .optJSONArray("sales") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            RemoteSale(
                id = row.optString("id"), invoiceNumber = row.optString("invoice_number").takeIf { it.isNotBlank() && it != "null" },
                status = row.optString("status"), totalMinor = row.optLong("total_minor"),
                paidMinor = row.optLong("paid_minor"), dueMinor = row.optLong("due_minor"),
                createdAt = row.optLong("created_at"),
                customerName = row.optString("customer_name").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    fun getReport(token: String, fromUnixSeconds: Long, toUnixSeconds: Long): RemoteReport {
        val result = request(
            "/v1/reports/summary?from=$fromUnixSeconds&to=$toUnixSeconds",
            "GET", token, null
        )
        return RemoteReport(
            saleCount = result.optInt("saleCount"),
            revenueMinor = result.optLong("revenueMinor"),
            paidMinor = result.optLong("paidMinor"),
            dueMinor = result.optLong("dueMinor")
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun request(path: String, method: String, token: String, payload: JSONObject?): JSONObject {
        val connection = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = try { JSONObject(responseText) } catch (_: Exception) {
                throw IOException("استجابة غير مفهومة من الخادم")
            }
            if (status !in 200..299) {
                val code = response.optString("error", "server_error")
                val message = when (code) {
                    "authentication_required" -> "انتهت صلاحية رمز الجهاز أو تم إيقافه"
                    "insufficient_stock" -> "الكمية المتاحة لا تكفي لإتمام البيع"
                    "sale_not_found_or_payment_exceeds_due" -> "المبلغ أكبر من المديونية المتبقية أو الفاتورة غير موجودة"
                    "barcode_already_exists" -> "الباركود مسجل بالفعل في هذه الصيدلية"
                    else -> "تعذر تنفيذ الطلب: $code"
                }
                throw IOException(message)
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
