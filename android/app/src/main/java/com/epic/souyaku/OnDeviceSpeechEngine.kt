package com.epic.souyaku

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SouyakuSpeechRecognitionResult(
    val text: String,
    val detectedLanguageTag: String? = null,
    val languageConfidenceLevel: Int = 0,
)

class SpeechRecognitionException(val errorCode: Int) :
    IllegalStateException("Speech recognition error ${speechRecognitionErrorName(errorCode)} ($errorCode)")

fun speechRecognitionErrorName(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
    SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
    else -> "ERROR_UNKNOWN"
}

/**
 * Prefers Android's explicit on-device recognizer. Interpreter calls never
 * fall back to a network recognizer. The interpreter may explicitly opt in to the system
 * recognizer when internet tools are enabled and on-device recognition is unavailable.
 *
 * Diagnostic Logcat tag: SOUYAKU-Speech
 */
class OnDeviceSpeechEngine(private val context: Context) {
    companion object {
        private const val TAG = "SOUYAKU-Speech"
        private val requestIds = AtomicLong(0L)
    }

    fun isOnDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun isAvailable(allowSystemFallback: Boolean = false): Boolean =
        isOnDeviceAvailable() || (allowSystemFallback && SpeechRecognizer.isRecognitionAvailable(context))

    fun modeLabel(allowSystemFallback: Boolean = false): String = when {
        isOnDeviceAvailable() -> "端末内音声認識"
        allowSystemFallback && SpeechRecognizer.isRecognitionAvailable(context) -> "Android標準音声認識"
        else -> "音声認識利用不可"
    }

    fun autoLanguageSwitchAvailable(): Boolean = Build.VERSION.SDK_INT >= 34

    suspend fun recognizeOnce(
        language: String = "ja-JP",
        timeoutMillis: Long = 15_000L,
        allowSystemFallback: Boolean = false,
    ): String = recognizeOnceDetailed(
        language = language,
        timeoutMillis = timeoutMillis,
        allowSystemFallback = allowSystemFallback,
    ).text

    suspend fun recognizeOnceDetailed(
        language: String = "ja-JP",
        timeoutMillis: Long = 15_000L,
        allowSystemFallback: Boolean = false,
        autoDetectLanguages: List<String> = emptyList(),
        preferOnDevice: Boolean = true,
        onPartial: ((String) -> Unit)? = null,
    ): SouyakuSpeechRecognitionResult = withTimeout(timeoutMillis) {
        withContext(Dispatchers.Main.immediate) {
            val requestId = requestIds.incrementAndGet()
            val onDeviceAvailable = isOnDeviceAvailable()
            val systemAvailable = SpeechRecognizer.isRecognitionAvailable(context)
            val systemFallbackAvailable = allowSystemFallback && systemAvailable
            val useOnDevice = onDeviceAvailable && (preferOnDevice || !systemFallbackAvailable)

            Log.i(
                TAG,
                "[$requestId] REQUEST begin language=$language timeoutMs=$timeoutMillis " +
                    "allowSystemFallback=$allowSystemFallback preferOnDevice=$preferOnDevice " +
                    "onDeviceAvailable=$onDeviceAvailable systemAvailable=$systemAvailable " +
                    "engine=${if (useOnDevice) "ON_DEVICE" else "SYSTEM"} " +
                    "autoCandidates=${autoDetectLanguages.joinToString(prefix = "[", postfix = "]")}",
            )

            if (!isAvailable(allowSystemFallback)) {
                Log.e(TAG, "[$requestId] REQUEST rejected: SpeechRecognizer unavailable")
                error("SpeechRecognizer is unavailable")
            }

            suspendCancellableCoroutine { continuation ->
                val createdAt = SystemClock.elapsedRealtime()
                val recognizer = try {
                    if (useOnDevice) {
                        Log.i(TAG, "[$requestId] CREATE engine=ON_DEVICE createOnDeviceSpeechRecognizer")
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        Log.w(TAG, "[$requestId] CREATE engine=SYSTEM createSpeechRecognizer (may use network)")
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "[$requestId] CREATE failed", t)
                    continuation.resumeWithException(t)
                    return@suspendCancellableCoroutine
                }

                var finished = false
                var detectedLanguageTag: String? = null
                var languageConfidenceLevel = 0
                var lastRmsLogAt = 0L

                fun elapsed(): Long = SystemClock.elapsedRealtime() - createdAt

                fun destroy(reason: String) {
                    Log.i(TAG, "[$requestId] DESTROY reason=$reason elapsedMs=${elapsed()}")
                    runCatching { recognizer.destroy() }
                        .onFailure { Log.e(TAG, "[$requestId] DESTROY failed", it) }
                }

                fun finish(reason: String, block: () -> Unit) {
                    if (finished) {
                        Log.w(TAG, "[$requestId] FINISH ignored duplicate reason=$reason elapsedMs=${elapsed()}")
                        return
                    }
                    finished = true
                    Log.i(TAG, "[$requestId] FINISH reason=$reason elapsedMs=${elapsed()}")
                    runCatching { block() }
                        .onFailure { Log.e(TAG, "[$requestId] FINISH continuation failed", it) }
                    destroy(reason)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.i(TAG, "[$requestId] CALLBACK onReadyForSpeech elapsedMs=${elapsed()} keys=${params?.keySet()?.sorted()}")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.i(TAG, "[$requestId] CALLBACK onBeginningOfSpeech elapsedMs=${elapsed()}")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRmsLogAt >= 500L) {
                            lastRmsLogAt = now
                            Log.d(TAG, "[$requestId] CALLBACK onRmsChanged rmsDb=${"%.1f".format(rmsdB)} elapsedMs=${elapsed()}")
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {
                        Log.v(TAG, "[$requestId] CALLBACK onBufferReceived bytes=${buffer?.size ?: 0} elapsedMs=${elapsed()}")
                    }

                    override fun onEndOfSpeech() {
                        Log.i(TAG, "[$requestId] CALLBACK onEndOfSpeech elapsedMs=${elapsed()}")
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty()
                        val partial = matches.firstOrNull().orEmpty().trim()
                        Log.d(TAG, "[$requestId] CALLBACK onPartialResults text=${matches.joinToString(" | ").take(240)} elapsedMs=${elapsed()}")
                        if (partial.isNotBlank()) onPartial?.invoke(partial)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {
                        Log.d(TAG, "[$requestId] CALLBACK onEvent type=$eventType keys=${params?.keySet()?.sorted()} elapsedMs=${elapsed()}")
                    }

                    override fun onLanguageDetection(results: Bundle) {
                        if (Build.VERSION.SDK_INT < 34) return
                        detectedLanguageTag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                        languageConfidenceLevel = results.getInt(
                            SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
                            SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN,
                        )
                        Log.i(
                            TAG,
                            "[$requestId] CALLBACK onLanguageDetection language=$detectedLanguageTag " +
                                "confidenceLevel=$languageConfidenceLevel keys=${results.keySet().sorted()} elapsedMs=${elapsed()}",
                        )
                    }

                    override fun onError(error: Int) {
                        val name = speechRecognitionErrorName(error)
                        Log.e(TAG, "[$requestId] CALLBACK onError code=$error name=$name elapsedMs=${elapsed()}")
                        finish("onError:$name") {
                            continuation.resumeWithException(SpeechRecognitionException(error))
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty()
                        val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val text = matches.firstOrNull().orEmpty().trim()
                        Log.i(
                            TAG,
                            "[$requestId] CALLBACK onResults matches=${matches.joinToString(" | ").take(400)} " +
                                "confidences=${confidences?.joinToString(prefix = "[", postfix = "]") ?: "null"} " +
                                "detectedLanguage=$detectedLanguageTag languageConfidenceLevel=$languageConfidenceLevel " +
                                "elapsedMs=${elapsed()}",
                        )
                        finish("onResults") {
                            continuation.resume(
                                SouyakuSpeechRecognitionResult(
                                    text = text,
                                    detectedLanguageTag = detectedLanguageTag,
                                    languageConfidenceLevel = languageConfidenceLevel,
                                ),
                            )
                        }
                    }
                })

                continuation.invokeOnCancellation { cause ->
                    if (!finished) {
                        finished = true
                        Log.w(
                            TAG,
                            "[$requestId] CANCEL coroutine cause=${cause?.javaClass?.simpleName}:${cause?.message} elapsedMs=${elapsed()}",
                        )
                        destroy("coroutine-cancel")
                    }
                }

                val requestIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, onPartial != null)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 350L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice)
                    }
                    if (Build.VERSION.SDK_INT >= 34 && autoDetectLanguages.isNotEmpty()) {
                        val allowed = ArrayList(autoDetectLanguages.distinct())
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                        putStringArrayListExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                            allowed,
                        )
                        putExtra(
                            RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                            RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
                        )
                        putStringArrayListExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                            allowed,
                        )
                    }
                }

                Log.i(
                    TAG,
                    "[$requestId] START startListening language=$language engine=${if (useOnDevice) "ON_DEVICE" else "SYSTEM"} " +
                        "preferOffline=$useOnDevice " +
                        "autoSwitch=${Build.VERSION.SDK_INT >= 34 && autoDetectLanguages.isNotEmpty()} elapsedMs=${elapsed()}",
                )
                try {
                    recognizer.startListening(requestIntent)
                    Log.i(TAG, "[$requestId] START submitted elapsedMs=${elapsed()}")
                } catch (t: Throwable) {
                    Log.e(TAG, "[$requestId] START threw ${t.javaClass.simpleName}: ${t.message}", t)
                    finish("startListening-exception") {
                        continuation.resumeWithException(t)
                    }
                }
            }
        }
    }
}
