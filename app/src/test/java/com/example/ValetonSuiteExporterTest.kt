package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TonePatchEntity
import com.example.dsp.CaptureMode
import com.example.dsp.ExportPatchRequest
import com.example.dsp.InstrumentMode
import com.example.dsp.LocalFileValetonFolderTarget
import com.example.dsp.ModelQuality
import com.example.dsp.PackExporter
import com.example.dsp.ValetonSuiteExporter
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ValetonSuiteExporterTest {

    private fun createDummyPatch(
        id: Long,
        defaultName: String,
        tempDir: File,
        isBass: Boolean = false
    ): TonePatchEntity {
        val namFile = File(tempDir, "src_$id.nam").apply {
            writeText("""{"version":"0.5.4","architecture":"WaveNet"}""")
        }
        val irFile = File(tempDir, "src_${id}_ir.wav").apply {
            writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00))
        }
        return TonePatchEntity(
            id = id,
            name = defaultName,
            instrumentType = if (isBass) InstrumentMode.BASS_4.code else InstrumentMode.GUITAR.code,
            sourceType = "FILE",
            sourceLabel = "source.wav",
            category = if (isBass) "Bass" else "High Gain",
            gp5Slot = "Sin asignar",
            isFavorite = false,
            captureMode = CaptureMode.AMP_PLUS_IR.code,
            modelQuality = ModelQuality.GP5_NANO.code,
            sampleRate = 48000,
            irSamples = 1024,
            namFilePath = namFile.absolutePath,
            irFilePath = irFile.absolutePath,
            spectralCurveJson = "{}",
            metricsJson = "{}",
            gp5RecipeJson = "{}"
        )
    }

    @Test
    fun exportsSequentiallyIntoEmptyPresetSlotsWithCustomArtistSongNames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempRoot = File(context.cacheDir, "valeton_suite_test").apply {
            deleteRecursively()
            mkdirs()
        }
        val suiteDir = File(tempRoot, "ValetonSuite").apply { mkdirs() }
        val presetsDir = File(suiteDir, "Presets").apply { mkdirs() }
        // Simulate existing occupied presets at P01, P02, and P04
        File(presetsDir, "P01_Factory_Clean.json").writeText("{}")
        File(presetsDir, "P02_Blues_Drive.json").writeText("{}")
        File(presetsDir, "P04_Jazz_Chorus.json").writeText("{}")

        val target = LocalFileValetonFolderTarget(suiteDir)
        assertEquals("P03", ValetonSuiteExporter.previewNextEmptySlot(target))

        val srcDir = File(tempRoot, "Sources").apply { mkdirs() }
        val patch1 = createDummyPatch(1L, "Tone_01", srcDir, isBass = false)
        val patch2 = createDummyPatch(2L, "Tone_02", srcDir, isBass = true)

        val requests = listOf(
            ExportPatchRequest(patch1, "Metallica - Master Of Puppets"),
            ExportPatchRequest(patch2, "Muse - Hysteria Bass")
        )

        val report = ValetonSuiteExporter.exportPatchesSequentially(target, requests)
        assertEquals(2, report.exportedEntries.size)

        // First empty slot was P03 (skipping occupied P01, P02), second empty slot was P05 (skipping occupied P04)
        val entry1 = report.exportedEntries[0]
        assertEquals("P03", entry1.assignedSlot)
        assertEquals("Metallica - Master Of Puppets.nam", entry1.namFileName)
        assertEquals("Metallica - Master Of Puppets_IR.wav", entry1.irFileName)
        assertEquals("P03_Metallica - Master Of Puppets.json", entry1.presetFileName)

        val entry2 = report.exportedEntries[1]
        assertEquals("P05", entry2.assignedSlot)
        assertEquals("Muse - Hysteria Bass.nam", entry2.namFileName)
        assertEquals("Muse - Hysteria Bass_IR.wav", entry2.irFileName)
        assertEquals("P05_Muse - Hysteria Bass.json", entry2.presetFileName)

        assertEquals("P06", report.nextEmptySlotAfterExport)

        // Verify actual files inside SnapTone/, UserIR/, Presets/
        assertTrue(File(suiteDir, "SnapTone/Metallica - Master Of Puppets.nam").exists())
        assertTrue(File(suiteDir, "UserIR/Metallica - Master Of Puppets_IR.wav").exists())
        val presetJson = JSONObject(File(suiteDir, "Presets/P03_Metallica - Master Of Puppets.json").readText())
        assertEquals("P03", presetJson.getString("slot_code"))
        assertEquals("Metallica - Master Of Puppets", presetJson.getString("preset_name"))
        assertEquals("SnapTone/Metallica - Master Of Puppets.nam", presetJson.getString("snap_tone_file"))

        tempRoot.deleteRecursively()
    }

    @Test
    fun sanitizeReadableFileNameBlocksTraversalAndZipUsesCustomNames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "zip_custom_name_test").apply { mkdirs() }

        assertEquals("Pink Floyd - Comfortably Numb", PackExporter.sanitizeReadableFileName("Pink Floyd - Comfortably Numb"))
        val safe = PackExporter.sanitizeReadableFileName("../../evil:path*name?.nam")
        assertTrue(!safe.contains("..") && !safe.contains("/") && !safe.contains(":"))

        val patch = createDummyPatch(10L, "Default_Patch", tempDir)
        val zipBytes = PackExporter.writeZipBundleBytesWithNames(
            listOf(patch to "John Mayer - Slow Dancing")
        )
        val entries = ArrayList<String>()
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries.add(e.name)
                e = zis.nextEntry
            }
        }
        assertTrue(entries.contains("John Mayer - Slow Dancing.nam"))
        assertTrue(entries.contains("John Mayer - Slow Dancing_IR.wav"))
        assertTrue(entries.contains("John Mayer - Slow Dancing_GP5_Guia.txt"))

        tempDir.deleteRecursively()
    }
}
