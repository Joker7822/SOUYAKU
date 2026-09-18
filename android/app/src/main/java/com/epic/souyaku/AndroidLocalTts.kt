package com.epic.souyaku

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Android TTS with explicit preference for voices that do not require a network connection. */
class AndroidLocalTts(context: Context) {
    private val contextCacheDir = context.applicationContext.cacheDir
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        lateinit var instance: TextToSpeech
        instance = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                if (!ready.isCompleted) ready.complete(false)
            } else {
                selectBestVoice(instance, "ja-JP", requireOffline = true)
                if (!ready.isCompleted) ready.complete(true)
            }
        }
        tts = instance
    }

    suspend fun isLocalReady(): Boolean = ready.await() && hasOfflineVoice("ja-JP")

    suspend fun hasOfflineVoice(languageTag: String): Boolean {
        if (!ready.await()) return false
        val language = Locale.forLanguageTag(languageTag).language
        return tts.voices?.any { it.locale.language == language && !it.isNetworkConnectionRequired } == true
    }

    suspend fun speak(text: String): Boolean = speak(text, "ja-JP", requireOffline = true)

    suspend fun speak(
        text: String,
        languageTag: String,
        requireOffline: Boolean = true,
        onStart: (() -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
        audioAttributes: AudioAttributes? = null,
    ): Boolean {
        if (text.isBlank() || !ready.await()) return false
        return withContext(Dispatchers.Main.immediate) {
            if (!selectBestVoice(tts, languageTag, requireOffline)) return@withContext false
            // Video interpretation can supply navigation-style attributes so a media app can
            // keep playing (typically ducked) instead of being treated like a competing media
            // player. The actual focus policy is still controlled by the installed TTS engine.
            if (audioAttributes != null) {
                runCatching { tts.setAudioAttributes(audioAttributes) }
            }
            suspendCancellableCoroutine { continuation ->
                val id = "souyaku-${UUID.randomUUID()}"
                val started = AtomicBoolean(false)
                val finished = AtomicBoolean(false)

                fun dispatch(callback: (() -> Unit)?) {
                    if (callback == null) return
                    if (Looper.myLooper() == Looper.getMainLooper()) callback()
                    else mainHandler.post { callback() }
                }

                fun complete(value: Boolean) {
                    if (!finished.compareAndSet(false, true)) return
                    dispatch(onFinished)
                    if (continuation.isActive) continuation.resume(value)
                }

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == id && started.compareAndSet(false, true)) dispatch(onStart)
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) complete(true)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == id) complete(false)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id) complete(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id) complete(false)
                    }
                })

                continuation.invokeOnCancellation {
                    runCatching { tts.stop() }
                    complete(false)
                }
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) complete(false)
            }
        }
    }


    /**
     * Synthesizes TTS to a temporary file and plays it through an explicitly requested output.
     * This avoids changing the global communication route and lets SOUYAKU request different
     * outputs for the two translation directions. Android audio policy may still override the
     * preferred device; the actual route is logged after playback starts.
     */
    suspend fun speakToDevice(
        text: String,
        languageTag: String,
        preferredDevice: AudioDeviceInfo,
        requireOffline: Boolean = true,
        audioAttributes: AudioAttributes? = null,
    ): Boolean {
        if (text.isBlank() || !ready.await()) return false
        return withContext(Dispatchers.Main.immediate) {
            if (!selectBestVoice(tts, languageTag, requireOffline)) return@withContext false
            val cacheFile = File(contextCacheDir, "souyaku-routed-${UUID.randomUUID()}.wav")
            try {
                val synthesized = synthesizeToFileInternal(text, cacheFile)
                if (!synthesized || !cacheFile.exists() || cacheFile.length() == 0L) return@withContext false
                playFileToDevice(cacheFile, preferredDevice, audioAttributes)
            } finally {
                runCatching { cacheFile.delete() }
            }
        }
    }

    private suspend fun synthesizeToFileInternal(text: String, file: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            val id = "souyaku-file-${UUID.randomUUID()}"
            val completed = AtomicBoolean(false)
            fun finish(value: Boolean) {
                if (completed.compareAndSet(false, true) && continuation.isActive) continuation.resume(value)
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id) finish(true) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id) finish(false) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id) finish(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) finish(false) }
            })
            continuation.invokeOnCancellation { runCatching { tts.stop() } }
            if (tts.synthesizeToFile(text, null, file, id) == TextToSpeech.ERROR) finish(false)
        }

    private suspend fun playFileToDevice(
        file: File,
        preferredDevice: AudioDeviceInfo,
        attributes: AudioAttributes?,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val player = MediaPlayer()
        val completed = AtomicBoolean(false)
        fun finish(value: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            runCatching { player.release() }
            if (continuation.isActive) continuation.resume(value)
        }
        try {
            player.setAudioAttributes(
                attributes ?: AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            val accepted = player.setPreferredDevice(preferredDevice)
            player.setOnCompletionListener { finish(true) }
            player.setOnErrorListener { _, _, _ -> finish(false); true }
            player.prepare()
            player.start()
            mainHandler.postDelayed({
                val routed = runCatching { player.routedDevice }.getOrNull()
                android.util.Log.i(
                    "SOUYAKU-RoutedTTS",
                    "preferred=${preferredDevice.productName}(id=${preferredDevice.id},type=${preferredDevice.type}) " +
                        "accepted=$accepted routed=${routed?.productName}(id=${routed?.id},type=${routed?.type})",
                )
            }, 120L)
            continuation.invokeOnCancellation {
                runCatching { player.stop() }
                finish(false)
            }
        } catch (t: Throwable) {
            android.util.Log.w("SOUYAKU-RoutedTTS", "playback failed", t)
            finish(false)
        }
    }

    fun stop() = runCatching { tts.stop() }
    fun shutdown() = runCatching { tts.shutdown() }

    private fun selectBestVoice(engine: TextToSpeech, languageTag: String, requireOffline: Boolean): Boolean {
        val locale = Locale.forLanguageTag(languageTag)
        val matching = engine.voices
            ?.filter { it.locale.language == locale.language }
            .orEmpty()
        val offline = matching.filter { !it.isNetworkConnectionRequired }
        val candidates = if (requireOffline || offline.isNotEmpty()) offline else matching
        val best = candidates.maxByOrNull { it.quality * 10_000 - it.latency }
        if (best != null) {
            engine.voice = best
            return true
        }
        if (requireOffline) return false
        return engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
    }
}
