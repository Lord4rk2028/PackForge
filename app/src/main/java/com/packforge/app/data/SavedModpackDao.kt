package com.packforge.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.packforge.app.domain.model.SavedModpack
import com.packforge.app.domain.model.SavedModpackSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedModpackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(modpack: SavedModpack)

    @Delete
    suspend fun delete(modpack: SavedModpack)

    /**
     * Lista LIGERA para la biblioteca: NO trae addonsJson ni resolutionsJson
     * (columnas que pueden pesar cientos de KB por fila). Si se incluyeran, un
     * historial con varias filas grandes reventaba el CursorWindow de 2 MB
     * ("Row too big to fit into CursorWindow") y la biblioteca aparecía vacía.
     * Las columnas omitidas se quedan con su valor por defecto ("") y se
     * rellenan al abrir el modpack con getById().
     */
    @Query(
        "SELECT id, name, author, version, mcVersion, description, " +
            "addonNames, addonCount, filePath, fileName, createdAt, " +
            "coverUriString, tags, conflictStrategy, " +
            "coalesce(substr(addonsJson, 1, 65536), '') AS sourceJsonHead " +
            "FROM saved_modpacks ORDER BY createdAt DESC"
    )
    fun getAll(): Flow<List<SavedModpackSummary>>

    @Query("SELECT * FROM saved_modpacks WHERE id = :id")
    suspend fun getById(id: String): SavedModpack?

    /**
     * Todas las filas COMPLETAS, incluyendo `addonsJson`.
     *
     * Pensada para el mantenimiento de almacenamiento, que necesita leer las rutas
     * (`sourceFilePath`) de cada addon antes de borrar nada. Al no ser un Flow ni
     * alimentar la biblioteca, el CursorWindow grande no supone un problema aquí.
     */
    @Query("SELECT * FROM saved_modpacks")
    suspend fun getAllFull(): List<SavedModpack>

    @Query("DELETE FROM saved_modpacks WHERE id = :id")
    suspend fun deleteById(id: String)
}
