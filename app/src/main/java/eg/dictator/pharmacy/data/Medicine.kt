package eg.dictator.pharmacy.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "medicines", indices = [Index("name"), Index(value = ["barcode"], unique = true), Index(value = ["remoteId"], unique = true)])
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    val sellingPrice: Double,
    val quantity: Int,
    val minimumQuantity: Int = 3,
    val barcode: String? = null,
    val imageUri: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val remoteId: String? = null
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

@Entity(tableName = "sales", indices = [Index("createdAt"), Index(value = ["remoteSaleId"], unique = true)])
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val total: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val remoteSaleId: String? = null
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


data class SaleWithItems(
    @androidx.room.Embedded val sale: Sale,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "saleId")
    val items: List<SaleItem>
)

data class TopSellingMedicine(
    val medicineId: Long,
    val medicineName: String,
    val soldQuantity: Int,
    val revenue: Double
)


@Entity(tableName = "customer_debts", indices = [Index("createdAt"), Index("customerName"), Index(value = ["remoteSaleId"], unique = true)])
data class CustomerDebt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerName: String,
    val totalAmount: Double,
    val paidAmount: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val remoteSaleId: String? = null
)

@Entity(
    tableName = "debt_items",
    foreignKeys = [ForeignKey(entity = CustomerDebt::class, parentColumns = ["id"], childColumns = ["debtId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("debtId"), Index("medicineId")]
)
data class DebtItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val debtId: Long,
    val medicineId: Long?,
    val medicineName: String,
    val unitPrice: Double,
    val quantity: Int,
    val lineTotal: Double
)

@Entity(
    tableName = "debt_payments",
    foreignKeys = [ForeignKey(entity = CustomerDebt::class, parentColumns = ["id"], childColumns = ["debtId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("debtId"), Index("createdAt")]
)
data class DebtPayment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val debtId: Long,
    val amount: Double,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class DebtLineRequest(val medicineId: Long, val quantity: Int)

data class CustomerDebtWithDetails(
    @androidx.room.Embedded val debt: CustomerDebt,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "debtId") val items: List<DebtItem>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "debtId") val payments: List<DebtPayment>
)


@Entity(
    tableName = "sale_returns",
    foreignKeys = [ForeignKey(entity = SaleItem::class, parentColumns = ["id"], childColumns = ["saleItemId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("saleItemId"), Index("createdAt")]
)
data class SaleReturn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleItemId: Long,
    val medicineId: Long,
    val quantity: Int,
    val refundAmount: Double,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis()
)


@Entity(
    tableName = "medicine_reference",
    indices = [Index("commercialNameAr"), Index("scientificName"), Index("manufacturer")]
)
data class MedicineReference(
    @PrimaryKey val id: Int,
    val commercialNameEn: String,
    val commercialNameAr: String,
    val scientificName: String,
    val manufacturer: String,
    val drugClass: String,
    val route: String,
    val priceEgp: Double?,
    val barcode: String? = null,
    @ColumnInfo(defaultValue = "''") val uses: String = ""
)
