package com.epic.souyaku

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation backed by ML Kit. The language model is downloaded once,
 * then translation stays on the device.
 */
class SouyakuTranslationEngine : Closeable {
    private var translator: Translator? = null
    private var activePair: Pair<String, String>? = null
    private var preparedPair: Pair<String, String>? = null

    suspend fun prepare(source: InterpreterLanguage, target: InterpreterLanguage, wifiOnly: Boolean = false) {
        require(source != target) { "Source and target languages must differ" }
        val sourceCode = TranslateLanguage.fromLanguageTag(source.translateTag)
            ?: error("Unsupported source language: ${source.translateTag}")
        val targetCode = TranslateLanguage.fromLanguageTag(target.translateTag)
            ?: error("Unsupported target language: ${target.translateTag}")
        val pair = sourceCode to targetCode
        if (activePair != pair || translator == null) {
            translator?.close()
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceCode)
                .setTargetLanguage(targetCode)
                .build()
            translator = Translation.getClient(options)
            activePair = pair
            preparedPair = null
        }
        if (preparedPair == pair) return
        val conditions = DownloadConditions.Builder().apply {
            if (wifiOnly) requireWifi()
        }.build()
        awaitVoid { success, failure ->
            translator!!.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { success() }
                .addOnFailureListener(failure)
        }
        preparedPair = pair
    }

    suspend fun translate(text: String): String {
        val input = text.trim()
        if (input.isBlank()) return ""
        val client = translator ?: error("Translation model is not prepared")
        return suspendCancellableCoroutine { continuation ->
            client.translate(input)
                .addOnSuccessListener { translated ->
                    if (continuation.isActive) continuation.resume(translated.trim())
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }

    override fun close() {
        translator?.close()
        translator = null
        activePair = null
        preparedPair = null
    }

    private suspend fun awaitVoid(register: (() -> Unit, (Exception) -> Unit) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            register(
                { if (continuation.isActive) continuation.resume(Unit) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) },
            )
        }
}
