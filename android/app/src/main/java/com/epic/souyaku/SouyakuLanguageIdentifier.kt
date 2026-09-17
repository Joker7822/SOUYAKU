package com.epic.souyaku

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SouyakuLanguageGuess(
    val language: InterpreterLanguage?,
    val confidence: Float,
    val rawTag: String,
)

/**
 * Text-side language identification for interpreter AUTO mode.
 * The bundled ML Kit model is available immediately after app installation.
 */
class SouyakuLanguageIdentifier : Closeable {
    private val client: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.15f)
            .build(),
    )

    suspend fun identify(text: String): SouyakuLanguageGuess {
        val input = text.trim()
        if (input.isBlank()) return SouyakuLanguageGuess(null, 0f, "und")
        return suspendCancellableCoroutine { continuation ->
            client.identifyPossibleLanguages(input)
                .addOnSuccessListener { candidates ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val best = candidates
                        .filter { it.languageTag != "und" }
                        .maxByOrNull { it.confidence }
                    if (best == null) {
                        continuation.resume(SouyakuLanguageGuess(null, 0f, "und"))
                    } else {
                        continuation.resume(
                            SouyakuLanguageGuess(
                                language = InterpreterLanguage.fromDetectedTag(best.languageTag),
                                confidence = best.confidence.coerceIn(0f, 1f),
                                rawTag = best.languageTag,
                            ),
                        )
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }

    override fun close() = client.close()
}

data class SouyakuLanguageDecision(
    val language: InterpreterLanguage?,
    val confidence: Float,
    val stable: Boolean,
    val source: String,
)

/**
 * Prevents AUTO mode from jumping languages because of short phrases such as OK/Yes/No.
 * SpeechRecognizer language detection and text Language ID are combined when both exist.
 */
class SouyakuLanguageStabilizer {
    private var stableLanguage: InterpreterLanguage? = null
    private var pendingLanguage: InterpreterLanguage? = null
    private var pendingCount: Int = 0

    fun reset() {
        stableLanguage = null
        pendingLanguage = null
        pendingCount = 0
    }

    fun stable(): InterpreterLanguage? = stableLanguage

    fun resolve(
        text: String,
        textGuess: SouyakuLanguageGuess,
        speechLanguageTag: String?,
        speechConfidenceLevel: Int,
    ): SouyakuLanguageDecision {
        val speechLanguage = InterpreterLanguage.fromDetectedTag(speechLanguageTag)
        val speechConfidence = when (speechConfidenceLevel) {
            3 -> 0.96f
            2 -> 0.84f
            1 -> 0.62f
            else -> if (speechLanguage != null) 0.55f else 0f
        }

        val textLanguage = textGuess.language
        val chosen = when {
            speechLanguage != null && speechLanguage == textLanguage -> {
                Candidate(speechLanguage, maxOf(speechConfidence, textGuess.confidence), "音声+テキスト")
            }
            textLanguage != null && textGuess.confidence >= 0.82f -> {
                Candidate(textLanguage, textGuess.confidence, "テキスト")
            }
            speechLanguage != null && speechConfidence >= 0.72f -> {
                Candidate(speechLanguage, speechConfidence, "音声")
            }
            textLanguage != null -> Candidate(textLanguage, textGuess.confidence, "テキスト")
            else -> null
        }

        if (chosen == null) {
            return SouyakuLanguageDecision(stableLanguage, 0f, stableLanguage != null, "不明")
        }

        val current = stableLanguage
        if (current == null) {
            val trimmedLength = text.trim().length
            val initialThreshold = if (trimmedLength <= 3) 0.92f else 0.74f
            if (chosen.confidence >= initialThreshold || (chosen.source == "音声+テキスト" && trimmedLength >= 2)) {
                stableLanguage = chosen.language
                pendingLanguage = null
                pendingCount = 0
                return SouyakuLanguageDecision(chosen.language, chosen.confidence, true, chosen.source)
            }
            return SouyakuLanguageDecision(null, chosen.confidence, false, "判定保留")
        }

        if (chosen.language == current) {
            pendingLanguage = null
            pendingCount = 0
            return SouyakuLanguageDecision(current, chosen.confidence, true, chosen.source)
        }

        val shortUtterance = text.trim().length <= 3
        val switchThreshold = if (shortUtterance) 0.98f else 0.90f

        if (chosen.language == pendingLanguage) pendingCount += 1
        else {
            pendingLanguage = chosen.language
            pendingCount = 1
        }

        // AUTO is deliberately sticky. A different language must be strong and repeated
        // before the lock moves, preventing short phrases and TTS echo from flipping it.
        if (chosen.confidence >= switchThreshold && pendingCount >= 3) {
            stableLanguage = chosen.language
            pendingLanguage = null
            pendingCount = 0
            return SouyakuLanguageDecision(chosen.language, chosen.confidence, true, chosen.source)
        }

        return SouyakuLanguageDecision(current, chosen.confidence, true, "固定言語を維持")
    }

    private data class Candidate(
        val language: InterpreterLanguage,
        val confidence: Float,
        val source: String,
    )
}
