package eg.dictator.pharmacy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Medicine::class, StockMovement::class, Sale::class, SaleItem::class, CustomerDebt::class, DebtItem::class, DebtPayment::class],
    version = 3,
    exportSchema = true
)
abstract class PharmacyDatabase : RoomDatabase() {
    abstract fun medicineDao(): MedicineDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS sales (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, total REAL NOT NULL, createdAt INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sales_createdAt ON sales(createdAt)")
                database.execSQL("CREATE TABLE IF NOT EXISTS sale_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, saleId INTEGER NOT NULL, medicineId INTEGER NOT NULL, medicineName TEXT NOT NULL, unitPrice REAL NOT NULL, quantity INTEGER NOT NULL, lineTotal REAL NOT NULL, FOREIGN KEY(saleId) REFERENCES sales(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sale_items_saleId ON sale_items(saleId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sale_items_medicineId ON sale_items(medicineId)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS customer_debts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, customerName TEXT NOT NULL, totalAmount REAL NOT NULL, paidAmount REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_customer_debts_createdAt ON customer_debts(createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_customer_debts_customerName ON customer_debts(customerName)")
                database.execSQL("CREATE TABLE IF NOT EXISTS debt_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, debtId INTEGER NOT NULL, medicineId INTEGER, medicineName TEXT NOT NULL, unitPrice REAL NOT NULL, quantity INTEGER NOT NULL, lineTotal REAL NOT NULL, FOREIGN KEY(debtId) REFERENCES customer_debts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_debt_items_debtId ON debt_items(debtId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_debt_items_medicineId ON debt_items(medicineId)")
                database.execSQL("CREATE TABLE IF NOT EXISTS debt_payments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, debtId INTEGER NOT NULL, amount REAL NOT NULL, note TEXT, createdAt INTEGER NOT NULL, FOREIGN KEY(debtId) REFERENCES customer_debts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_debt_payments_debtId ON debt_payments(debtId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_debt_payments_createdAt ON debt_payments(createdAt)")
            }
        }

        @Volatile private var instance: PharmacyDatabase? = null
        fun get(context: Context, pharmacyId: String): PharmacyDatabase = Room.databaseBuilder(
            context.applicationContext,
            PharmacyDatabase::class.java,
            "dictator_pharmacy_" + pharmacyId.replace("-", "") + ".db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
}
