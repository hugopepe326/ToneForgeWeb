package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tone_patches")
data class TonePatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val instrumentType: String = "GUITAR", // "GUITAR" or "BASS_4"
    val sourceType: String, // "FILE", "LINK", "MIC"
    val sourceLabel: String,
    val category: String, // "Clean", "Crunch", "High Gain", "Lead", "Bass"
    val gp5Slot: String, // e.g. "P01".."P99" or "Sin asignar"
    val isFavorite: Boolean = false,
    val captureMode: String, // "FULL_RIG" or "AMP_PLUS_IR"
    val modelQuality: String, // "GP5_NANO" or "STUDIO_LITE"
    val sampleRate: Int, // 48000 or 44100
    val irSamples: Int, // 1024 or 2048
    val namFilePath: String,
    val irFilePath: String,
    val spectralCurveJson: String, // JSON with refDb[], modeledDb[], freqsHz[]
    val metricsJson: String, // JSON with ToneMetrics
    val gp5RecipeJson: String, // JSON with Gp5SignalChain
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
