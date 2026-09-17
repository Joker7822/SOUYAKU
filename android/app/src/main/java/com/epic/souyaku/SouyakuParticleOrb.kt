package com.epic.souyaku

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val SouyakuBlue = Color(0xFF67D9FF)
private val SouyakuViolet = Color(0xFF8B7CFF)
private val SouyakuWhite = Color(0xFFEAF9FF)
private val SouyakuError = Color(0xFFFF6E7D)
private val SouyakuApproval = Color(0xFFFFD166)

@Composable
fun SouyakuParticleCharacter(
    state: SouyakuVisualState,
    modifier: Modifier = Modifier,
    size: Dp = 244.dp,
    audioEnergy: Float = 0f,
    onClick: (() -> Unit)? = null,
    showLabel: Boolean = true,
    startupProgress: Float = 1f,
    labelAlpha: Float = 1f,
) {
    val target = SouyakuOrbPhysics.target(state)
    val duration = if (state == SouyakuVisualState.ERROR) 260 else 620
    @Composable
    fun animated(value: Float): Float {
        val v by animateFloatAsState(value, tween(durationMillis = duration), label = "souyaku-orb")
        return v
    }
    val motion = SouyakuOrbMotion(
        rotation = animated(target.rotation),
        turbulence = animated(target.turbulence),
        pulse = animated(target.pulse),
        spiral = animated(target.spiral),
        expansion = animated(target.expansion),
        jitter = animated(target.jitter),
        ringPull = animated(target.ringPull),
        brightness = animated(target.brightness),
    )
    val energy = animated(audioEnergy.coerceIn(0f, 1f))

    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = 0L
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { frame ->
                if (start == 0L) start = frame
                time = (frame - start) / 1_000_000_000f
            }
        }
    }

    val stateLabel = stateLabel(state)
    var canvasModifier = Modifier
        .size(size)
        .semantics { contentDescription = "SOUYAKU particle character, $stateLabel" }
    if (onClick != null) canvasModifier = canvasModifier.clickable(onClick = onClick)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = canvasModifier) {
                drawSouyakuOrb(time, motion, energy, state, startupProgress)
            }
        }
        if (showLabel) {
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                modifier = Modifier.alpha(0.94f * labelAlpha.coerceIn(0f, 1f)),
            )
        }
    }
}

private fun DrawScope.drawSouyakuOrb(
    time: Float,
    motion: SouyakuOrbMotion,
    audioEnergy: Float,
    state: SouyakuVisualState,
    startupProgress: Float,
) {
    val dimension = min(size.width, size.height)
    val center = Offset(size.width / 2f, size.height / 2f)
    val fieldRadius = dimension * 0.455f
    val accent = when (state) {
        SouyakuVisualState.ERROR -> SouyakuError
        SouyakuVisualState.APPROVAL -> SouyakuApproval
        else -> SouyakuBlue
    }

    val sample = FloatArray(5)
    val count = 288
    for (i in 0 until count) {
        SouyakuOrbPhysics.sampleInto(i, count, time, motion, audioEnergy, sample)
        val intro = startupTransform(startupProgress, i)
        val pos = Offset(
            x = center.x + (sample[0] * intro.scale + intro.offsetX) * fieldRadius,
            y = center.y + (sample[1] * intro.scale + intro.offsetY) * fieldRadius,
        )
        val radius = sample[2] * density * (0.76f + intro.opacity * 0.24f)
        val alpha = sample[3] * intro.opacity
        val localEnergy = sample[4]
        val particleColor = if (((i * 17 + i / 5) % 11) < 6) SouyakuBlue else SouyakuViolet

        drawCircle(
            color = particleColor.copy(alpha = alpha * 0.075f * motion.brightness),
            radius = radius * (3.9f + localEnergy * 2.1f),
            center = pos,
        )
        drawCircle(
            color = particleColor.copy(alpha = alpha * 0.52f * motion.brightness),
            radius = radius * 1.72f,
            center = pos,
        )
        drawCircle(
            color = SouyakuWhite.copy(alpha = alpha * (0.46f + localEnergy * 0.32f)),
            radius = radius * 0.58f,
            center = pos,
        )
    }

    val satellites = 36
    for (i in 0 until satellites) {
        SouyakuOrbPhysics.sampleInto(1000 + i, satellites, time * 0.72f, motion, audioEnergy, sample)
        val scale = 1.03f + ((i * 7) % 9) * 0.019f
        val intro = startupTransform(startupProgress, 1000 + i)
        val pos = Offset(
            x = center.x + (sample[0] * scale * intro.scale + intro.offsetX) * fieldRadius,
            y = center.y + (sample[1] * scale * intro.scale + intro.offsetY) * fieldRadius,
        )
        drawCircle(
            color = accent.copy(alpha = sample[3] * intro.opacity * 0.20f * motion.brightness),
            radius = sample[2] * density * 0.82f,
            center = pos,
        )
    }
}

private data class StartupTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val opacity: Float,
)

private fun startupTransform(progress: Float, index: Int): StartupTransform {
    val p = progress.coerceIn(0f, 1f)
    val gather = smoothStep(0.02f, 0.64f, p)
    val settle = smoothStep(0.82f, 1.0f, p)
    val pulsePhase = ((p - 0.64f) / 0.20f).coerceIn(0f, 1f)
    val pulse = if (p in 0.64f..0.84f) sin(pulsePhase * PI.toFloat()) else 0f

    val seed = ((index * 73 + 19) % 997) / 997f
    val angle = seed * PI.toFloat() * 2f + (1f - gather) * 1.15f
    val scatter = (1f - gather) * (0.20f + seed * 0.22f)
    val initialScale = 1.92f - 0.92f * gather
    val pulseScale = 1f + pulse * 0.105f * (1f - settle * 0.35f)
    val opacity = (0.06f + gather * 0.94f).coerceIn(0f, 1f)

    return StartupTransform(
        scale = initialScale * pulseScale,
        offsetX = cos(angle) * scatter,
        offsetY = sin(angle) * scatter,
        opacity = opacity,
    )
}

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

fun stateLabel(state: SouyakuVisualState): String = when (state) {
    SouyakuVisualState.DORMANT -> "休止"
    SouyakuVisualState.IDLE -> "待機中"
    SouyakuVisualState.LISTENING -> "聞いています"
    SouyakuVisualState.THINKING -> "翻訳中"
    SouyakuVisualState.SPEAKING -> "通訳音声出力中"
    SouyakuVisualState.APPROVAL -> "承認待ち"
    SouyakuVisualState.ERROR -> "状態を確認してください"
}
