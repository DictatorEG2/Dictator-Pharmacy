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

    @Query("SELECT id, name, category, sellingPrice, quantity, minimumQuantity, barcode, imageUri, updatedAt FROM medicines WHERE quantity <= 0 ORDER BY name COLLATE NOCASE LIMIT 500")
    abstract fun outOfStock(): Flow<List<Medicine>>

    @Query("SELECT id, name, category, sellingPrice, quantity, minimumQuantity, barcode, imageUri, updatedAt FROM medicines WHERE quantity > 0 AND quantity <= minimumQuantity ORDER BY quantity ASC LIMIT 500")
    abstract fun lowStock(): Flow<List<Medicine>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertMedicine(medicine: Medicine): Long

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
}
