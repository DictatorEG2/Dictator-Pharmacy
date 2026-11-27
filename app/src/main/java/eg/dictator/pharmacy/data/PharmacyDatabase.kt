package eg.dictator.pharmacy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Medicine::class, StockMovement::class, Sale::class, SaleItem::class],
    version = 2,
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

        @Volatile private var instance: PharmacyDatabase? = null
        fun get(context: Context): PharmacyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PharmacyDatabase::class.java, "dictator_pharmacy.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
