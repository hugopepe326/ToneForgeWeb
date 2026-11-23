package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.example.dsp.SpectralCurveData
import com.example.ui.theme.CopperAmber
import com.example.ui.theme.CopperAmberBright
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.IvoryText
import com.example.ui.theme.MutedSteel
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PrecisionTeal
import com.example.ui.theme.RackCard
import com.example.ui.theme.TitaniumText
import kotlin.math.abs
import kotlin.math.ln

@Composable
fun InteractiveWaveformTrimmer(
    peaks: List<Float>,
    durationSec: Float,
    trimStartFrac: Float,
    trimEndFrac: Float,
    onRangeChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableStateOf(1f) }
    var draggingStart by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianBg)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VENTANA DE ANÁLISIS",
                style = MaterialTheme.typography.labelMedium,
                color = TitaniumText
            )
            val selStart = trimStartFrac * durationSec
            val selEnd = trimEndFrac * durationSec
            Text(
                text = "${String.format("%.1f", selStart)}s — ${String.format("%.1f", selEnd)}s (${String.format("%.1f", selEnd - selStart)}s)",
                style = MaterialTheme.typography.labelMedium,
                color = CopperAmberBright
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(trimStartFrac, trimEndFrac) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val frac = (offset.x / widthPx).coerceIn(0f, 1f)
                            draggingStart = abs(frac - trimStartFrac) <= abs(frac - trimEndFrac)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val delta = dragAmount / widthPx
                            if (draggingStart) {
                                onRangeChange((trimStartFrac + delta).coerceIn(0f, trimEndFrac - 0.05f), trimEndFrac)
                            } else {
                                onRangeChange(trimStartFrac, (trimEndFrac + delta).coerceIn(trimStartFrac + 0.05f, 1f))
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midY = h / 2f

                // Selection highlight band
                val startX = trimStartFrac * w
                val endX = trimEndFrac * w
                drawRect(
                    color = CopperAmber.copy(alpha = 0.12f),
                    topLeft = Offset(startX, 0f),
                    size = Size((endX - startX).coerceAtLeast(2f), h)
                )

                // Center zero line
                drawLine(
                    color = HairlineBorder,
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1.dp.toPx()
                )

                // Waveform bars
                if (peaks.isNotEmpty()) {
                    val barStep = w / peaks.size
                    val barW = (barStep * 0.62f).coerceAtLeast(2f)
                    for ((i, amp) in peaks.withIndex()) {
                        val x = i * barStep + barStep * 0.5f
                        val frac = i.toFloat() / peaks.size
                        val inside = frac in trimStartFrac..trimEndFrac
                        val barH = (amp * h * 0.84f).coerceAtLeast(4f)
                        drawRoundRect(
                            color = if (inside) CopperAmber else MutedSteel.copy(alpha = 0.45f),
                            topLeft = Offset(x - barW / 2f, midY - barH / 2f),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                        )
                    }
                }

                // Start & End trim handles
                drawLine(
                    color = CopperAmberBright,
                    start = Offset(startX, 0f),
                    end = Offset(startX, h),
                    strokeWidth = 2.5.dp.toPx()
                )
                drawCircle(
                    color = CopperAmberBright,
                    radius = 6.dp.toPx(),
                    center = Offset(startX, 10.dp.toPx())
                )

                drawLine(
                    color = PrecisionTeal,
                    start = Offset(endX, 0f),
                    end = Offset(endX, h),
                    strokeWidth = 2.5.dp.toPx()
                )
                drawCircle(
                    color = PrecisionTeal,
                    radius = 6.dp.toPx(),
                    center = Offset(endX, h - 10.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Arrastra los bordes para aislar el riff o solo de guitarra",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedSteel
        )
    }
}

@Composable
fun SpectralResponseChart(
    curve: SpectralCurveData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RackCard)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RESPUESTA ESPECTRAL (30 Hz – 14 kHz)",
                style = MaterialTheme.typography.labelMedium,
                color = TitaniumText
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(color = CopperAmber, label = "Ref")
                Spacer(modifier = Modifier.width(10.dp))
                LegendDot(color = PrecisionTeal, label = ".nam")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ObsidianBg)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val freqs = curve.freqsHz
                if (freqs.size < 2) return@Canvas

                val minLog = ln(30f)
                val maxLog = ln(14000f)
                fun freqToX(f: Float): Float {
                    val norm = ((ln(f.coerceIn(30f, 14000f)) - minLog) / (maxLog - minLog))
                    return norm * w
                }
                fun dbToY(db: Float): Float {
                    // Range -24 dB .. +18 dB
                    val norm = ((db.coerceIn(-24f, 18f) + 24f) / 42f)
                    return h - (norm * h)
                }

                // Grid lines at -12, 0, +12 dB
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                for (dbLine in listOf(-12f, 0f, 12f)) {
                    val y = dbToY(dbLine)
                    drawLine(
                        color = if (dbLine == 0f) HairlineBorder else HairlineBorder.copy(alpha = 0.55f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = if (dbLine == 0f) null else dash
                    )
                }

                // Vertical frequency markers: 41.2 (E1), 100, 500, 1k, 3k, 8k
                for (fGrid in listOf(41.2f, 100f, 500f, 1000f, 3000f, 8000f)) {
                    val x = freqToX(fGrid)
                    drawLine(
                        color = HairlineBorder.copy(alpha = 0.45f),
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Reference Path + subtle area fill
                val refPath = Path()
                val fillPath = Path()
                freqs.forEachIndexed { idx, f ->
                    val x = freqToX(f)
                    val y = dbToY(curve.referenceDb.getOrElse(idx) { 0f })
                    if (idx == 0) {
                        refPath.moveTo(x, y)
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, y)
                    } else {
                        refPath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                    if (idx == freqs.lastIndex) {
                        fillPath.lineTo(x, h)
                        fillPath.close()
                    }
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(CopperAmber.copy(alpha = 0.22f), Color.Transparent)
                    )
                )
                drawPath(
                    path = refPath,
                    color = CopperAmber,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Modeled WaveNet .nam Path
                val modPath = Path()
                freqs.forEachIndexed { idx, f ->
                    val x = freqToX(f)
                    val y = dbToY(curve.modeledDb.getOrElse(idx) { 0f })
                    if (idx == 0) modPath.moveTo(x, y) else modPath.lineTo(x, y)
                }
                drawPath(
                    path = modPath,
                    color = PrecisionTeal,
                    style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("30 Hz", "55 Hz", "100 Hz", "500 Hz", "1 kHz", "3 kHz", "8 kHz", "14 kHz").forEach { lbl ->
                Text(
                    text = lbl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSteel
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = IvoryText
        )
    }
}

@Composable
fun LiveVuMeterBar(
    levelNorm: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(ObsidianBg)
            .border(1.dp, HairlineBorder, RoundedCornerShape(5.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fillW = size.width * levelNorm.coerceIn(0f, 1f)
            if (fillW > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(PrecisionTeal, CopperAmber, Color(0xFFE05252))
                    ),
                    size = Size(fillW, size.height)
                )
            }
        }
    }
}
