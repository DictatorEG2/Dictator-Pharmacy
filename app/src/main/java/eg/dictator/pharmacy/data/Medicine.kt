package eg.dictator.pharmacy.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "medicines", indices = [Index("name"), Index(value = ["barcode"], unique = true)])
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    val sellingPrice: Double,
    val quantity: Int,
    val minimumQuantity: Int = 3,
    val barcode: String? = null,
    val imageUri: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "stock_movements", indices = [Index("medicineId"), Index("createdAt")])
data class StockMovement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val delta: Int,
    val reason: String,
    val actor: String = "local-user",
    val createdAt: Long = System.currentTimeMillis()
)
