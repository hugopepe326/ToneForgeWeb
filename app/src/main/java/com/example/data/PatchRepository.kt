package com.example.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Lightweight projection of core patch metadata persisted in Room:
 * name, instrument type, creation timestamp, and local file path(s).
 */
data class PatchMetadata(
    val id: Long = 0L,
    val name: String,
    val instrumentType: String,
    val createdAt: Long,
    val filePath: String,
    val irFilePath: String = ""
)

fun TonePatchEntity.toPatchMetadata(): PatchMetadata = PatchMetadata(
    id = id,
    name = name,
    instrumentType = instrumentType,
    createdAt = createdAt,
    filePath = namFilePath,
    irFilePath = irFilePath
)

/**
 * Room-backed repository responsible for storing, querying, updating, and deleting
 * local SnapTone/NAM patch metadata (name, instrument type, creation date, file path)
 * along with associated GP-5 DSP settings.
 */
class PatchRepository(private val dao: TonePatchDao) {

    val allPatches: Flow<List<TonePatchEntity>> = dao.getAllPatches()

    val allPatchMetadata: Flow<List<PatchMetadata>> = dao.getAllPatches().map { entities ->
        entities.map { it.toPatchMetadata() }
    }

    val favoritePatches: Flow<List<TonePatchEntity>> = dao.observeFavoritePatches()

    val patchCount: Flow<Int> = dao.observePatchCount()

    fun getPatchesByInstrument(instrumentType: String): Flow<List<TonePatchEntity>> =
        dao.observePatchesByInstrument(instrumentType)

    fun searchPatches(query: String): Flow<List<TonePatchEntity>> =
        dao.searchPatches(query.trim())

    fun getPatchesCreatedBetween(startTimestampMs: Long, endTimestampMs: Long): Flow<List<TonePatchEntity>> =
        dao.observePatchesCreatedBetween(startTimestampMs, endTimestampMs)

    suspend fun getById(id: Long): TonePatchEntity? = dao.getPatchById(id)

    suspend fun getMetadataById(id: Long): PatchMetadata? =
        dao.getPatchById(id)?.toPatchMetadata()

    suspend fun getByFilePath(filePath: String): TonePatchEntity? =
        dao.getPatchByFilePath(filePath.trim())

    /**
     * Stores metadata for a newly created patch in the local Room database.
     */
    suspend fun savePatchMetadata(
        name: String,
        instrumentType: String,
        filePath: String,
        irFilePath: String = "",
        createdAt: Long = System.currentTimeMillis(),
        gp5Slot: String = "Sin asignar",
        category: String = if (instrumentType == "BASS_4") "Bass" else "Crunch",
        notes: String = ""
    ): Long {
        val cleanName = name.trim()
        val cleanPath = filePath.trim()
        require(cleanName.isNotEmpty()) { "El nombre del parche no puede estar vacío." }
        require(cleanPath.isNotEmpty()) { "La ruta del archivo del parche no puede estar vacía." }

        val normalizedInstrument = instrumentType.trim().ifEmpty { "GUITAR" }
        val entity = TonePatchEntity(
            name = cleanName,
            instrumentType = normalizedInstrument,
            sourceType = "FILE",
            sourceLabel = File(cleanPath).name,
            category = category,
            gp5Slot = gp5Slot,
            isFavorite = false,
            captureMode = "FULL_RIG",
            modelQuality = "GP5_NANO",
            sampleRate = 48000,
            irSamples = 1024,
            namFilePath = cleanPath,
            irFilePath = irFilePath.trim(),
            spectralCurveJson = "{}",
            metricsJson = "{}",
            gp5RecipeJson = "{}",
            notes = notes.trim(),
            createdAt = createdAt
        )
        return dao.insertPatch(entity)
    }

    suspend fun insert(patch: TonePatchEntity): Long {
        require(patch.name.isNotBlank()) { "El nombre del parche no puede estar vacío." }
        require(patch.namFilePath.isNotBlank()) { "La ruta del archivo del parche no puede estar vacía." }
        return dao.insertPatch(patch)
    }

    suspend fun update(patch: TonePatchEntity) = dao.updatePatch(patch)

    suspend fun updateMetadata(
        id: Long,
        name: String,
        instrumentType: String,
        filePath: String,
        irFilePath: String = ""
    ) {
        val cleanName = name.trim()
        val cleanPath = filePath.trim()
        require(cleanName.isNotEmpty()) { "El nombre del parche no puede estar vacío." }
        require(cleanPath.isNotEmpty()) { "La ruta del archivo del parche no puede estar vacía." }
        dao.updatePatchMetadata(
            id = id,
            name = cleanName,
            instrumentType = instrumentType.trim().ifEmpty { "GUITAR" },
            namFilePath = cleanPath,
            irFilePath = irFilePath.trim()
        )
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)

    suspend fun updateSlot(id: Long, slot: String) = dao.updateGp5Slot(id, slot)

    suspend fun delete(patch: TonePatchEntity) {
        withContext(Dispatchers.IO) {
            if (patch.namFilePath.isNotBlank()) {
                runCatching { File(patch.namFilePath).delete() }
            }
            if (patch.irFilePath.isNotBlank()) {
                runCatching { File(patch.irFilePath).delete() }
            }
        }
        dao.deletePatchById(patch.id)
    }

    suspend fun deleteById(id: Long): Boolean {
        val existing = dao.getPatchById(id) ?: return false
        delete(existing)
        return true
    }

    suspend fun deleteAll() {
        dao.deleteAllPatches()
    }

    companion object {
        fun create(context: Context): PatchRepository =
            PatchRepository(ToneDatabase.getInstance(context).tonePatchDao())
    }
}
