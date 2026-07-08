package net.guardian.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TrafficEntity::class, AlertEntity::class], version = 1)
abstract class TrafficDatabase : RoomDatabase() {
    abstract fun trafficDao(): TrafficDao

    companion object {
        @Volatile
        private var INSTANCE: TrafficDatabase? = null

        fun getDatabase(context: Context): TrafficDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TrafficDatabase::class.java,
                    "netguardian_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
