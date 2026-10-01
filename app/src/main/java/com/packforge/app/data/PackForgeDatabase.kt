package com.packforge.app.data

import android.content.Context
import android.database.CursorWindow
import android.os.Build
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
                // Aumentar el tamaño del CursorWindow: Room/SQLite usa por defecto 2 MB por ventana.
                // Los modpacks con addonsJson grande (manifests completos de versiones anteriores)
                // pueden superar ese límite y provocar "Row too big to fit into CursorWindow",
                // que hacía fallar la lectura de la biblioteca entera y parecer que los modpacks
                // se habían borrado cuando en realidad seguían en la base de datos.
                // setCursorWindowSize existe desde API 28 (P), pero no siempre es visible en el
                // SDK público (API restringida en compilaciones recientes), así que se
                // invoca por reflexión. Si falla no es crítico: la compactación de filas
                // gigantes en el ViewModel evita que el cursor de 2 MB se desborde.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val method = CursorWindow::class.java
                            .getMethod("setCursorWindowSize", Long::class.javaPrimitiveType)
                        method.invoke(null, 64L * 1024 * 1024) // 64 MB
                    } catch (_: Throwable) { /* ignorar si la API no lo permite */ }
                }
                instance
            }
        }
    }
}
