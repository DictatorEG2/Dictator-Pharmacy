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
    abstract suspend fun insertDebt(debt: CustomerDebt): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertDebtItems(items: List<DebtItem>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertDebtPayment(payment: DebtPayment)

    @Query("UPDATE customer_debts SET paidAmount = :paid, updatedAt = :now WHERE id = :debtId AND paidAmount <= totalAmount AND :paid <= totalAmount")
    abstract suspend fun updateDebtPaidAmount(debtId: Long, paid: Double, now: Long): Int

    @Query("SELECT id, customerName, totalAmount, paidAmount, createdAt, updatedAt FROM customer_debts WHERE paidAmount < totalAmount ORDER BY CASE WHEN :sort = 0 THEN customerName END COLLATE NOCASE ASC, CASE WHEN :sort = 1 THEN (totalAmount - paidAmount) END DESC, CASE WHEN :sort = 2 THEN createdAt END DESC, CASE WHEN :sort = 3 THEN createdAt END ASC, createdAt DESC LIMIT 500")
    abstract fun activeDebts(sort: Int): Flow<List<CustomerDebtWithDetails>>

    @Query("SELECT id, customerName, totalAmount, paidAmount, createdAt, updatedAt FROM customer_debts WHERE paidAmount >= totalAmount ORDER BY createdAt DESC LIMIT 100")
    abstract fun paidDebts(): Flow<List<CustomerDebtWithDetails>>

    @Query("SELECT id, customerName, totalAmount, paidAmount, createdAt, updatedAt FROM customer_debts WHERE id = :id LIMIT 1")
    abstract suspend fun debtById(id: Long): CustomerDebt?
    
    @Transaction
    open suspend fun createDebt(customerName: String, lines: List<DebtLineRequest>, initialPayment: Double): Long {
        require(customerName.isNotBlank()) { "اكتب اسم العميل" }
        require(lines.isNotEmpty() && lines.all { it.quantity > 0 }) { "أضف صنفًا واحدًا على الأقل" }
        require(initialPayment >= 0.0) { "الدفعة غير صحيحة" }
        val merged = lines.groupBy { it.medicineId }.mapValues { (_, rows) -> rows.sumOf { it.quantity } }
        val details = merged.map { (id, quantity) ->
            val medicine = medicineById(id) ?: error("أحد الأصناف لم يعد موجودًا")
            require(medicine.sellingPrice >= 0.0) { "سعر غير صالح" }
            Triple(medicine, quantity, medicine.sellingPrice)
        }
        val total = details.sumOf { (_, quantity, price) -> quantity * price }
        require(total > 0.0 && initialPayment <= total) { "الدفعة أكبر من إجمالي المديونية" }
        val now = System.currentTimeMillis()
        val debtId = insertDebt(CustomerDebt(customerName = customerName.trim(), totalAmount = total, paidAmount = initialPayment, createdAt = now, updatedAt = now))
        // A purchase recorded as debt is still a sale: deduct stock in the same
        // Room transaction so the same units cannot be sold a second time.
        details.forEach { (medicine, quantity, _) ->
            check(updateQuantity(medicine.id, -quantity, now) == 1) {
                "تعذر خصم مخزون الصنف: ${medicine.name}"
            }
            insertMovement(
                StockMovement(
                    medicineId = medicine.id,
                    delta = -quantity,
                    reason = "بيع آجل #$debtId",
                    createdAt = now
                )
            )
        }
        insertDebtItems(details.map { (medicine, quantity, price) ->
            DebtItem(debtId = debtId, medicineId = medicine.id, medicineName = medicine.name, unitPrice = price, quantity = quantity, lineTotal = price * quantity)
        })
        if (initialPayment > 0.0) insertDebtPayment(DebtPayment(debtId = debtId, amount = initialPayment, note = "دفعة عند إنشاء المديونية", createdAt = now))
        return debtId
    }

    @Transaction
    open suspend fun recordDebtPayment(debtId: Long, amount: Double) {
        require(amount.isFinite() && amount > 0.0) { "أدخل مبلغًا صحيحًا أكبر من صفر" }
        val debt = debtById(debtId) ?: error("المديونية غير موجودة")
        val remaining = debt.totalAmount - debt.paidAmount
        require(amount <= remaining + 0.000001) { "المبلغ أكبر من المتبقي: %.2f".format(remaining) }
        val now = System.currentTimeMillis()
        check(updateDebtPaidAmount(debtId, debt.paidAmount + amount, now) == 1) { "تعذر تحديث المديونية" }
        insertDebtPayment(DebtPayment(debtId = debtId, amount = amount, note = "دفعة جزئية", createdAt = now))
    }

    @Transaction
    open suspend fun markDebtPaid(debtId: Long) {
        val debt = debtById(debtId) ?: error("المديونية غير موجودة")
        val remaining = debt.totalAmount - debt.paidAmount
        if (remaining <= 0.000001) return
        val now = System.currentTimeMillis()
        check(updateDebtPaidAmount(debtId, debt.totalAmount, now) == 1) { "تعذر تسوية المديونية" }
        insertDebtPayment(DebtPayment(debtId = debtId, amount = remaining, note = "تسوية كاملة", createdAt = now))
    }

    @Transaction
    @Query("SELECT * FROM sales ORDER BY createdAt DESC LIMIT 100")
    abstract fun recentSales(): Flow<List<SaleWithItems>>

    @Query("SELECT medicineId, medicineName, SUM(quantity) AS soldQuantity, SUM(lineTotal) AS revenue FROM sale_items INNER JOIN sales ON sales.id = sale_items.saleId WHERE sales.createdAt >= :fromTime AND sales.createdAt < :toTime GROUP BY medicineId, medicineName ORDER BY soldQuantity DESC, revenue DESC LIMIT 10")
    abstract fun topSelling(fromTime: Long, toTime: Long): Flow<List<TopSellingMedicine>>

    @Query("SELECT COALESCE(SUM(total), 0) FROM sales WHERE createdAt >= :fromTime AND createdAt < :toTime")
    abstract fun revenueBetween(fromTime: Long, toTime: Long): Flow<Double>

    @Query("SELECT COUNT(*) FROM sales WHERE createdAt >= :fromTime AND createdAt < :toTime")
    abstract fun salesCountBetween(fromTime: Long, toTime: Long): Flow<Int>

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
