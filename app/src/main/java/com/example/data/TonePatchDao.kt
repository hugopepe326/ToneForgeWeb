package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TonePatchDao {
    @Query("SELECT * FROM tone_patches ORDER BY createdAt DESC")
    fun getAllPatches(): Flow<List<TonePatchEntity>>

    @Query("SELECT * FROM tone_patches WHERE instrumentType = :instrumentType ORDER BY createdAt DESC")
    fun observePatchesByInstrument(instrumentType: String): Flow<List<TonePatchEntity>>

    @Query(
        "SELECT * FROM tone_patches WHERE name LIKE '%' || :query || '%' " +
            "OR gp5Slot LIKE '%' || :query || '%' " +
            "OR namFilePath LIKE '%' || :query || '%' " +
            "OR notes LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC"
    )
    fun searchPatches(query: String): Flow<List<TonePatchEntity>>

    @Query(
        "SELECT * FROM tone_patches WHERE createdAt BETWEEN :startMs AND :endMs " +
            "ORDER BY createdAt DESC"
    )
    fun observePatchesCreatedBetween(startMs: Long, endMs: Long): Flow<List<TonePatchEntity>>

    @Query("SELECT * FROM tone_patches WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun observeFavoritePatches(): Flow<List<TonePatchEntity>>

    @Query("SELECT COUNT(*) FROM tone_patches")
    fun observePatchCount(): Flow<Int>

    @Query("SELECT * FROM tone_patches WHERE id = :id LIMIT 1")
    suspend fun getPatchById(id: Long): TonePatchEntity?

    @Query("SELECT * FROM tone_patches WHERE namFilePath = :filePath OR irFilePath = :filePath LIMIT 1")
    suspend fun getPatchByFilePath(filePath: String): TonePatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatch(patch: TonePatchEntity): Long

    @Update
    suspend fun updatePatch(patch: TonePatchEntity)

    @Query(
        "UPDATE tone_patches SET name = :name, instrumentType = :instrumentType, " +
            "namFilePath = :namFilePath, irFilePath = :irFilePath WHERE id = :id"
    )
    suspend fun updatePatchMetadata(
        id: Long,
        name: String,
        instrumentType: String,
        namFilePath: String,
        irFilePath: String
    )

    @Query("DELETE FROM tone_patches WHERE id = :id")
    suspend fun deletePatchById(id: Long)

    @Query("DELETE FROM tone_patches")
    suspend fun deleteAllPatches()

    @Query("UPDATE tone_patches SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE tone_patches SET gp5Slot = :slot WHERE id = :id")
    suspend fun updateGp5Slot(id: Long, slot: String)
}
