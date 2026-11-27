package eg.dictator.pharmacy.data

import androidx.room.Entity
import androidx.room.ForeignKey
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

@Entity(tableName = "sales", indices = [Index("createdAt")])
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val total: Double,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sale_items",
    foreignKeys = [ForeignKey(entity = Sale::class, parentColumns = ["id"], childColumns = ["saleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("saleId"), Index("medicineId")]
)
data class SaleItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val medicineId: Long,
    val medicineName: String,
    val unitPrice: Double,
    val quantity: Int,
    val lineTotal: Double
)

data class SaleLineRequest(val medicineId: Long, val quantity: Int)
