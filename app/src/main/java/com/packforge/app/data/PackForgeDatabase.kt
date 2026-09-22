package com.packforge.app.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.packforge.app.domain.model.SavedModpack

@Database(
    entities = [SavedModpack::class],
    version = 3,
    exportSchema = true
)
abstract class PackForgeDatabase : RoomDatabase() {

    abstract fun savedModpackDao(): SavedModpackDao

    companion object {
        @Volatile
        private var INSTANCE: PackForgeDatabase? = null

        /**
         * Migración 2→3: agrega columnas de resolución de conflictos.
         * Estas columnas son opcionales (default vacío/KEEP_FIRST) para compatibilidad
         * con modpacks guardados antes de esta migración.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_STATEMENTS.forEach { db.execSQL(it) }
            }
        }

        /**
         * Sentencias exactas de la migración 2→3, expuestas para test unitario
         * ([DatabaseMigrationTest]) sin necesidad de instrumentación.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        val MIGRATION_2_3_STATEMENTS = listOf(
            "ALTER TABLE saved_modpacks ADD COLUMN resolutionsJson TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE saved_modpacks ADD COLUMN conflictStrategy TEXT NOT NULL DEFAULT 'KEEP_FIRST'"
        )

        /** Alias legible para el test (evita acceder al companion con backticks). */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        fun migration23Statements(): List<String> = MIGRATION_2_3_STATEMENTS

        fun getInstance(context: Context): PackForgeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PackForgeDatabase::class.java,
                    "packforge_database"
                )
                .addMigrations(MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
