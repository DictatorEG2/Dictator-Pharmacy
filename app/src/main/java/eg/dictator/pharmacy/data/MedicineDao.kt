package eg.dictator.pharmacy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MedicineDao {
    @Query("SELECT id, name, category, sellingPrice, quantity, minimumQuantity, barcode, imageUri, updatedAt FROM medicines WHERE name LIKE '%' || :query || '%' COLLATE NOCASE OR category LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY name COLLATE NOCASE LIMIT 100")
    abstract fun search(query: String): Flow<List<Medicine>>

    @Query("SELECT id, name, category, sellingPrice, quantity, minimumQuantity, barcode, imageUri, updatedAt FROM medicines ORDER BY name COLLATE NOCASE LIMIT 500")
    abstract fun allMedicines(): Flow<List<Medicine>>

    @Query("SELECT id, name, category, sellingPrice, quantity, minimumQuantity, barcode, imageUri, updatedAt FROM medicines WHERE quantity <= 0 ORDER BY name COLLATE NOCASE LIMIT 500")
    abstract fun outOfStock(): Flow<List<Medicine>>

    @Query("SELECT id, name, category, sellingPrice, quantity, minimumQuantity, barcode, imageUri, updatedAt FROM medicines WHERE quantity > 0 AND quantity <= minimumQuantity ORDER BY quantity ASC LIMIT 500")
    abstract fun lowStock(): Flow<List<Medicine>>

    @Query("SELECT id, name, category, sellingPrice, quantity, minimumQuantity, barcode, imageUri, updatedAt FROM medicines WHERE id = :id LIMIT 1")
    abstract suspend fun medicineById(id: Long): Medicine?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertMedicine(medicine: Medicine): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSale(sale: Sale): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSaleItems(items: List<SaleItem>)

    @Query("UPDATE medicines SET quantity = quantity + :delta, updatedAt = :now WHERE id = :id AND quantity + :delta >= 0")
    abstract suspend fun updateQuantity(id: Long, delta: Int, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertMovement(movement: StockMovement)

    @Transaction
    open suspend fun adjustStock(id: Long, delta: Int, reason: String): Boolean {
        if (delta == 0) return false
        val changed = updateQuantity(id, delta, System.currentTimeMillis())
        if (changed != 1) return false
        insertMovement(StockMovement(medicineId = id, delta = delta, reason = reason))
        return true
    }

    @Transaction
    open suspend fun completeSale(lines: List<SaleLineRequest>): Long {
        require(lines.isNotEmpty()) { "السلة فارغة" }
        require(lines.all { it.quantity > 0 }) { "الكمية يجب أن تكون أكبر من صفر" }
        val merged = lines.groupBy { it.medicineId }.mapValues { (_, entries) -> entries.sumOf { it.quantity } }
        val medicines = merged.map { (id, quantity) ->
            val medicine = medicineById(id) ?: error("أحد الأصناف لم يعد موجودًا")
            require(medicine.quantity >= quantity) { "الكمية غير كافية للصنف: ${medicine.name}" }
            require(medicine.sellingPrice >= 0.0) { "سعر غير صالح للصنف: ${medicine.name}" }
            Triple(medicine, quantity, medicine.sellingPrice)
        }
        val now = System.currentTimeMillis()
        val total = medicines.sumOf { (_, quantity, price) -> price * quantity }
        val saleId = insertSale(Sale(total = total, createdAt = now))
        insertSaleItems(medicines.map { (medicine, quantity, price) ->
            SaleItem(
                saleId = saleId,
                medicineId = medicine.id,
                medicineName = medicine.name,
                unitPrice = price,
                quantity = quantity,
                lineTotal = price * quantity
            )
        })
        medicines.forEach { (medicine, quantity, _) ->
            check(updateQuantity(medicine.id, -quantity, now) == 1) {
                "تعذر خصم مخزون الصنف: ${medicine.name}"
            }
            insertMovement(StockMovement(medicineId = medicine.id, delta = -quantity, reason = "بيع #$saleId", createdAt = now))
        }
        return saleId
    }
}
