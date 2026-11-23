package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.PatchRepository
import com.example.data.ToneDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PatchRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: ToneDatabase
    private lateinit var repository: PatchRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ToneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PatchRepository(db.tonePatchDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveAndObservePatchMetadataOrderedByCreationDate() {
        runBlocking {
            val idGuitar = repository.savePatchMetadata(
                name = "Brit Crunch 800",
                instrumentType = "GUITAR",
                filePath = "/storage/emulated/0/ToneForge/brit_800.nam",
                irFilePath = "/storage/emulated/0/ToneForge/brit_800_ir.wav",
                createdAt = 1_700_000_100_000L,
                gp5Slot = "P01"
            )
            val idBass = repository.savePatchMetadata(
                name = "SVT 4-String E1",
                instrumentType = "BASS_4",
                filePath = "/storage/emulated/0/ToneForge/svt_bass.nam",
                irFilePath = "/storage/emulated/0/ToneForge/svt_bass_ir.wav",
                createdAt = 1_700_000_200_000L,
                gp5Slot = "P02"
            )

            assertTrue(idGuitar > 0L)
            assertTrue(idBass > 0L)

            val metadataList = repository.allPatchMetadata.first()
            assertEquals(2, metadataList.size)
            // Most recent first
            assertEquals("SVT 4-String E1", metadataList[0].name)
            assertEquals("BASS_4", metadataList[0].instrumentType)
            assertEquals(1_700_000_200_000L, metadataList[0].createdAt)
            assertEquals("/storage/emulated/0/ToneForge/svt_bass.nam", metadataList[0].filePath)

            assertEquals("Brit Crunch 800", metadataList[1].name)
            assertEquals("GUITAR", metadataList[1].instrumentType)
            assertEquals(1_700_000_100_000L, metadataList[1].createdAt)
        }
    }

    @Test
    fun filterByInstrumentDateRangeSearchAndLookupByPath() {
        runBlocking {
            repository.savePatchMetadata(
                name = "Clean Twin",
                instrumentType = "GUITAR",
                filePath = "/tmp/clean_twin.nam",
                createdAt = 1000L
            )
            repository.savePatchMetadata(
                name = "Jazz Bass DI",
                instrumentType = "BASS_4",
                filePath = "/tmp/jazz_bass.nam",
                createdAt = 2000L
            )
            repository.savePatchMetadata(
                name = "Modern Metal",
                instrumentType = "GUITAR",
                filePath = "/tmp/metal.nam",
                createdAt = 5000L
            )

            val bassOnly = repository.getPatchesByInstrument("BASS_4").first()
            assertEquals(1, bassOnly.size)
            assertEquals("Jazz Bass DI", bassOnly.first().name)

            val range = repository.getPatchesCreatedBetween(1500L, 3000L).first()
            assertEquals(1, range.size)
            assertEquals("Jazz Bass DI", range.first().name)

            val searchHits = repository.searchPatches("metal").first()
            assertEquals(1, searchHits.size)
            assertEquals("Modern Metal", searchHits.first().name)

            val byPath = repository.getByFilePath("/tmp/clean_twin.nam")
            assertNotNull(byPath)
            assertEquals("Clean Twin", byPath!!.name)
        }
    }

    @Test
    fun updateAndDeletePatchCleansUpDatabaseAndLocalFiles() {
        runBlocking {
            val tempDir = File(context.cacheDir, "repo_test_files").apply { mkdirs() }
            val namFile = File(tempDir, "test_patch.nam").apply { writeText("{}") }
            val irFile = File(tempDir, "test_patch_ir.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            val id = repository.savePatchMetadata(
                name = "Old Name",
                instrumentType = "GUITAR",
                filePath = namFile.absolutePath,
                irFilePath = irFile.absolutePath
            )

            repository.updateMetadata(
                id = id,
                name = "Renamed Bass Patch",
                instrumentType = "BASS_4",
                filePath = namFile.absolutePath,
                irFilePath = irFile.absolutePath
            )
            repository.setFavorite(id, true)
            repository.updateSlot(id, "P14")

            val updated = repository.getById(id)!!
            assertEquals("Renamed Bass Patch", updated.name)
            assertEquals("BASS_4", updated.instrumentType)
            assertTrue(updated.isFavorite)
            assertEquals("P14", updated.gp5Slot)

            val deleted = repository.deleteById(id)
            assertTrue(deleted)
            assertNull(repository.getById(id))
            assertFalse(namFile.exists())
            assertFalse(irFile.exists())

            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsBlankNameOrBlankFilePath() {
        runBlocking {
            val blankName = runCatching {
                repository.savePatchMetadata(
                    name = "   ",
                    instrumentType = "GUITAR",
                    filePath = "/tmp/valid.nam"
                )
            }
            assertTrue(blankName.isFailure)

            val blankPath = runCatching {
                repository.savePatchMetadata(
                    name = "Valid Name",
                    instrumentType = "BASS_4",
                    filePath = ""
                )
            }
            assertTrue(blankPath.isFailure)
        }
    }
}
