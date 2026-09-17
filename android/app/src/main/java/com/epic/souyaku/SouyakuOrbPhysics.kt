package com.epic.souyaku

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin motion field for the SOUYAKU particle character.
 *
 * This intentionally contains no Android/Compose APIs so its behavior can be
 * regression-tested without an Android SDK. Positions are normalized around
 * the center of the orb (-1..1-ish). The UI layer decides colors and pixels.
 */
enum class SouyakuVisualState {
    DORMANT,
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    APPROVAL,
    ERROR,
}

data class SouyakuOrbMotion(
    val rotation: Float,
    val turbulence: Float,
    val pulse: Float,
    val spiral: Float,
    val expansion: Float,
    val jitter: Float,
    val ringPull: Float,
    val brightness: Float,
)

object SouyakuOrbPhysics {
    private const val TAU = (PI * 2.0).toFloat()

    fun target(state: SouyakuVisualState): SouyakuOrbMotion = when (state) {
        SouyakuVisualState.DORMANT -> SouyakuOrbMotion(
            rotation = 0.05f, turbulence = 0.12f, pulse = 0.10f, spiral = 0.02f,
            expansion = -0.06f, jitter = 0.00f, ringPull = 0.08f, brightness = 0.48f,
        )
        SouyakuVisualState.IDLE -> SouyakuOrbMotion(
            rotation = 0.15f, turbulence = 0.28f, pulse = 0.28f, spiral = 0.08f,
            expansion = 0.00f, jitter = 0.00f, ringPull = 0.12f, brightness = 0.72f,
        )
        SouyakuVisualState.LISTENING -> SouyakuOrbMotion(
            rotation = 0.30f, turbulence = 0.48f, pulse = 0.74f, spiral = 0.10f,
            expansion = 0.08f, jitter = 0.02f, ringPull = 0.06f, brightness = 0.92f,
        )
        SouyakuVisualState.THINKING -> SouyakuOrbMotion(
            rotation = 0.92f, turbulence = 0.40f, pulse = 0.20f, spiral = 0.92f,
            expansion = -0.07f, jitter = 0.01f, ringPull = 0.24f, brightness = 0.86f,
        )
        SouyakuVisualState.SPEAKING -> SouyakuOrbMotion(
            rotation = 0.24f, turbulence = 0.58f, pulse = 1.00f, spiral = 0.10f,
            expansion = 0.05f, jitter = 0.01f, ringPull = 0.08f, brightness = 1.00f,
        )
        SouyakuVisualState.APPROVAL -> SouyakuOrbMotion(
            rotation = 0.07f, turbulence = 0.14f, pulse = 0.22f, spiral = 0.02f,
            expansion = -0.02f, jitter = 0.00f, ringPull = 0.68f, brightness = 0.82f,
        )
        SouyakuVisualState.ERROR -> SouyakuOrbMotion(
            rotation = 0.18f, turbulence = 0.92f, pulse = 0.48f, spiral = 0.28f,
            expansion = 0.01f, jitter = 0.92f, ringPull = 0.04f, brightness = 0.88f,
        )
    }

    fun lerp(a: SouyakuOrbMotion, b: SouyakuOrbMotion, t: Float): SouyakuOrbMotion {
        val p = t.coerceIn(0f, 1f)
        fun f(x: Float, y: Float) = x + (y - x) * p
        return SouyakuOrbMotion(
            rotation = f(a.rotation, b.rotation),
            turbulence = f(a.turbulence, b.turbulence),
            pulse = f(a.pulse, b.pulse),
            spiral = f(a.spiral, b.spiral),
            expansion = f(a.expansion, b.expansion),
            jitter = f(a.jitter, b.jitter),
            ringPull = f(a.ringPull, b.ringPull),
            brightness = f(a.brightness, b.brightness),
        )
    }

    /** out = [x, y, normalizedSize, alpha, energy] */
    fun sampleInto(
        index: Int,
        count: Int,
        timeSeconds: Float,
        motion: SouyakuOrbMotion,
        audioEnergy: Float,
        out: FloatArray,
    ) {
        require(out.size >= 5)
        val seedA = hash(index, 0.37f)
        val seedR = hash(index, 2.91f)
        val seedP = hash(index, 7.13f)
        val seedC = hash(index, 11.73f)
        val seedD = hash(index, 19.41f)

        val evenAngle = TAU * index.toFloat() / count.toFloat()
        val angularWarp = (seedA - 0.5f) * 0.86f + sin(evenAngle * 2.0f + seedC * TAU) * 0.17f
        val baseAngle = evenAngle + angularWarp
        val densityCurve = sqrt(seedR.coerceIn(0f, 1f))
        val clusterBias = 0.86f + (seedC - 0.5f) * 0.30f + sin(baseAngle * 3.0f + seedD * 5.0f) * 0.07f
        val rawRadius = (densityCurve * clusterBias).coerceIn(0.035f, 0.96f)
        val ringRadius = 0.62f + (seedR - 0.5f) * 0.23f + (seedD - 0.5f) * 0.08f
        var radius = rawRadius + (ringRadius - rawRadius) * motion.ringPull * 0.66f

        val slowWave = sin(baseAngle * (2.35f + seedD) + timeSeconds * (1.05f + seedC * 0.72f) + seedP * TAU)
        val crossWave = cos(baseAngle * (4.2f + seedC * 1.8f) - timeSeconds * (0.69f + seedD * 0.58f) + seedA * 5.0f)
        val radialDrift = sin(timeSeconds * (0.34f + seedD * 0.45f) + seedC * 13.0f) * 0.018f
        radius *= 1.0f + motion.turbulence * (slowWave * 0.058f + crossWave * 0.039f)
        radius += radialDrift * (0.30f + motion.turbulence)

        val pulseWave = sin(timeSeconds * (3.7f + audioEnergy * 3.8f + seedC * 0.45f) - rawRadius * 10.5f + seedP * 2.2f)
        radius += motion.pulse * pulseWave * (0.020f + rawRadius * 0.036f)
        radius += motion.expansion * (0.43f + rawRadius * 0.57f)

        var angle = baseAngle + timeSeconds * motion.rotation * (0.38f + seedR * 1.16f)
        angle += motion.spiral * (1.0f - rawRadius) * sin(timeSeconds * (1.35f + seedD * 0.65f) + rawRadius * 8.0f + seedC * 2.0f) * 0.84f
        angle += motion.turbulence * sin(timeSeconds * (0.52f + seedC * 0.49f) + seedP * TAU) * 0.145f
        angle += sin(timeSeconds * 0.23f + seedD * TAU) * (seedC - 0.5f) * 0.075f

        val jitterX = sin(timeSeconds * (15.0f + seedA * 19.0f) + seedP * 31.0f) * motion.jitter * 0.052f
        val jitterY = cos(timeSeconds * (17.0f + seedR * 16.0f) + seedA * 27.0f) * motion.jitter * 0.052f

        out[0] = cos(angle) * radius + jitterX
        out[1] = sin(angle) * radius + jitterY
        out[2] = 0.48f + seedP * 1.55f + seedD * 0.38f + motion.brightness * 0.48f + audioEnergy * 0.32f
        out[3] = (0.11f + seedA * 0.53f + seedC * 0.13f + motion.brightness * 0.24f).coerceIn(0.08f, 0.95f)
        out[4] = ((slowWave + 1.0f) * 0.5f * 0.55f + audioEnergy * 0.45f).coerceIn(0f, 1f)
    }

    private fun hash(index: Int, salt: Float): Float {
        val v = sin(index * 12.9898 + salt * 78.233) * 43758.5453
        return (v - floor(v)).toFloat()
    }
}
