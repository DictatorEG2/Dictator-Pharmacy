package eg.dictator.pharmacy.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.net.HttpURLConnection

/**
 * Imports the CC0 Egyptian medicine reference list into each pharmacy's local
 * Room database once. Reference prices are informational only; pharmacy
 * selling prices are entered separately when adding stock.
 */
object MedicineReferenceImporter {
    suspend fun ensureImported(context: Context, dao: MedicineDao): Int = withContext(Dispatchers.IO) {
        val existing = dao.referenceCount()
        val prefs = context.getSharedPreferences("medicine_reference_sync", Context.MODE_PRIVATE)
        val lastSync = prefs.getLong("last_sync", 0L)
        val shouldRefresh = System.currentTimeMillis() - lastSync > 24L * 60L * 60L * 1000L
        val source = if (existing == 0) "asset" else if (shouldRefresh) "network" else "none"
        if (source == "none") return@withContext existing
        val input = try {
            if (source == "asset") context.assets.open("egyptian-drugs.csv")
            else {
                val connection = URL("https://raw.githubusercontent.com/karem505/egyptian-drug-database/main/data/egyptian-drugs.csv").openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = true
                connection.connect()
                if (connection.responseCode !in 200..299) error("Medicine catalog HTTP " + connection.responseCode)
                connection.inputStream
            }
        } catch (_: Exception) {
            return@withContext existing
        }
        var inserted = 0
        var lastId = 0
        input.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val header = reader.readLine()?.let(::parseCsvLine) ?: return@withContext 0
                val columns = header.mapIndexed { index, name -> name.trim().removePrefix("\uFEFF") to index }.toMap()
                val required = listOf("commercial_name_en", "commercial_name_ar", "scientific_name", "manufacturer", "drug_class", "route", "price_egp")
                if (required.any { it !in columns }) return@withContext 0
                var rowId = 0
                val batch = ArrayList<MedicineReference>(500)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val cells = parseCsvLine(line)
                    fun value(key: String) = cells.getOrNull(columns.getValue(key)).orEmpty().trim()
                    val en = value("commercial_name_en")
                    if (en.isBlank()) continue
                    rowId++
                    lastId = rowId
                    batch += MedicineReference(
                        id = rowId,
                        commercialNameEn = en,
                        commercialNameAr = value("commercial_name_ar"),
                        scientificName = value("scientific_name"),
                        manufacturer = value("manufacturer"),
                        drugClass = value("drug_class"),
                        route = value("route"),
                        priceEgp = value("price_egp").replace(",", "").toDoubleOrNull()
                    )
                    if (batch.size == 500) {
                        dao.insertMedicineReferences(batch.toList())
                        inserted += batch.size
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) {
                    dao.insertMedicineReferences(batch)
                    inserted += batch.size
                }
            }
        }
        if (inserted > 0) {
            dao.pruneMedicineReferences(lastId)
            prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
        }
        inserted + existing
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += field.toString()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        result += field.toString()
        return result
    }
}
