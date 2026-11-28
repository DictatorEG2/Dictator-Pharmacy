package eg.dictator.pharmacy.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.net.HttpURLConnection

/**
 * Imports the CC BY 4.0 Egyptian medicine reference list into each pharmacy's local
 * Room database once. Reference prices are informational only; pharmacy
 * selling prices are entered separately when adding stock.
 */
object MedicineReferenceImporter {
    suspend fun ensureImported(context: Context, dao: MedicineDao): Int = withContext(Dispatchers.IO) {
        val existing = dao.referenceCount()
        val prefs = context.getSharedPreferences("medicine_reference_sync", Context.MODE_PRIVATE)
        val lastSync = prefs.getLong("last_sync", 0L)
        val catalogVersion = prefs.getInt("catalog_version", 0)
        val shouldRefresh = System.currentTimeMillis() - lastSync > 24L * 60L * 60L * 1000L
        // Force the larger barcode-enabled catalogue into databases created by older app versions.
        val source = if (existing == 0 || catalogVersion < 2) "asset" else if (shouldRefresh) "network" else "none"
        if (source == "none") return@withContext existing
        val input = try {
            if (source == "asset") context.assets.open("egyptian-drugs.csv")
            else {
                val connection = URL("https://raw.githubusercontent.com/mahmoudfalous/eg-drugs/main/data/eg_drugs.csv").openConnection() as HttpURLConnection
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
                val required = listOf("id", "name", "arabic", "active", "company", "price", "barcode", "description", "uses")
                if (required.any { it !in columns }) return@withContext 0
                var rowId = 0
                val batch = ArrayList<MedicineReference>(500)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val cells = parseCsvLine(line)
                    fun value(key: String) = cells.getOrNull(columns.getValue(key)).orEmpty().trim()
                    val en = value("name")
                    if (en.isBlank()) continue
                    rowId++
                    val recordId = value("id").toIntOrNull() ?: rowId
                    lastId = maxOf(lastId, recordId)
                    batch += MedicineReference(
                        id = recordId,
                        commercialNameEn = en,
                        commercialNameAr = value("arabic"),
                        scientificName = value("active"),
                        manufacturer = value("company"),
                        drugClass = value("description"),
                        route = value("units"),
                        priceEgp = value("price").replace(",", "").toDoubleOrNull(),
                        barcode = value("barcode").takeIf { it.isNotBlank() },
                        uses = value("uses")
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
            prefs.edit().putLong("last_sync", System.currentTimeMillis()).putInt("catalog_version", 2).apply()
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
