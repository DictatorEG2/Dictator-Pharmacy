package eg.dictator.pharmacy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Medicine::class, StockMovement::class], version = 1, exportSchema = true)
abstract class PharmacyDatabase : RoomDatabase() {
    abstract fun medicineDao(): MedicineDao

    companion object {
        @Volatile private var instance: PharmacyDatabase? = null
        fun get(context: Context): PharmacyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PharmacyDatabase::class.java, "dictator_pharmacy.db")
                .build().also { instance = it }
        }
    }
}
