package com.packforge.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.packforge.app.domain.model.SavedModpack
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedModpackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(modpack: SavedModpack)

    @Delete
    suspend fun delete(modpack: SavedModpack)

    @Query("SELECT * FROM saved_modpacks ORDER BY createdAt DESC")
    fun getAll(): Flow<List<SavedModpack>>

    @Query("SELECT * FROM saved_modpacks WHERE id = :id")
    suspend fun getById(id: String): SavedModpack?

    @Query("DELETE FROM saved_modpacks WHERE id = :id")
    suspend fun deleteById(id: String)
}
