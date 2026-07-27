package com.packforge.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.packforge.app.domain.model.SavedModpack

@Database(
    entities = [SavedModpack::class],
    version = 2,
    exportSchema = false
)
abstract class PackForgeDatabase : RoomDatabase() {

    abstract fun savedModpackDao(): SavedModpackDao

    companion object {
        @Volatile
        private var INSTANCE: PackForgeDatabase? = null

        fun getInstance(context: Context): PackForgeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PackForgeDatabase::class.java,
                    "packforge_database"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
