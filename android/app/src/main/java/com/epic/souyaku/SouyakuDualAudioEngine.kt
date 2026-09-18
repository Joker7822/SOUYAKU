package com.epic.souyaku

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SOUYAKU dual-input audio API.
 *
 * On supported Android 13+ devices this keeps two independent AudioRecord instances alive:
 *  - SELF: headset/earphone microphone
 *  - PARTNER: built-in device microphone
 *
 * PCM is fanned out into independent ParcelFileDescriptor pipes so two SpeechRecognizer
 * instances can consume the streams concurrently through RecognizerIntent.EXTRA_AUDIO_SOURCE.
 *
 * Android/OEM audio policy is authoritative. The engine only reports ACTIVE when both records
 * actually route to the requested distinct devices after recording starts. Otherwise callers
 * must fall back to the legacy turn-based routing mode.
 */
class SouyakuDualAudioEngine(context: Context) {
    enum class Channel { SELF, PARTNER }

    data class Capability(
        val supportedByApi: Boolean,
        val earphoneInput: AudioDeviceInfo? = null,
        val deviceInput: AudioDeviceInfo? = null,
        val earphoneOutput: AudioDeviceInfo? = null,
        val speakerOutput: AudioDeviceInfo? = null,
        val dualInputActive: Boolean = false,
        val message: String,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val running = AtomicBoolean(false)

    @Volatile private var selfRecord: AudioRecord? = null
    @Volatile private var partnerRecord: AudioRecord? = null
    @Volatile private var selfAec: AcousticEchoCanceler? = null
    @Volatile private var partnerAec: AcousticEchoCanceler? = null

    private val selfSinks = CopyOnWriteArrayList<ParcelFileDescriptor.AutoCloseOutputStream>()
    private val partnerSinks = CopyOnWriteArrayList<ParcelFileDescriptor.AutoCloseOutputStream>()
    private var selfThread: Thread? = null
    private var partnerThread: Thread? = null

    fun inspect(): Capability {
        val apiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val earIn = inputs.firstOrNull(::isEarphoneInput)
        val deviceIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val earOut = outputs.firstOrNull(::isEarphoneOutput)
        val speakerOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val ready = apiSupported && earIn != null && deviceIn != null && earOut != null && speakerOut != null
        return Capability(
            supportedByApi = apiSupported,
            earphoneInput = earIn,
            deviceInput = deviceIn,
            earphoneOutput = earOut,
            speakerOutput = speakerOut,
            dualInputActive = false,
            message = when {
                !apiSupported -> "デュアル音声分離はAndroid 13以降で利用できます"
                earIn == null -> "イヤフォンマイクが見つかりません"
                deviceIn == null -> "デバイス内蔵マイクが見つかりません"
                earOut == null -> "イヤフォン出力が見つかりません"
                speakerOut == null -> "デバイススピーカーが見つかりません"
                ready -> "デュアル音声分離をテストできます"
                else -> "デュアル音声分離を利用できません"
            },
        )
    }

    suspend fun start(): Capability = withContext(Dispatchers.IO) {
        stopInternal("restart")
        val base = inspect()
        if (!base.supportedByApi || base.earphoneInput == null || base.deviceInput == null ||
            base.earphoneOutput == null || base.speakerOutput == null
        ) return@withContext base

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext base.copy(message = "マイク権限が必要です")
        }

        val self = createRecord(base.earphoneInput, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        val partner = createRecord(base.deviceInput, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (self == null || partner == null) {
            runCatching { self?.release() }
            runCatching { partner?.release() }
            return@withContext base.copy(message = "2本のAudioRecordを作成できません。従来モードへ戻ります")
        }

        try {
            self.startRecording()
            partner.startRecording()
            // Force both input tracks to pull at least one frame so OEM routing is materialized
            // before checking getRoutedDevice().
            val probe = ByteArray(640)
            self.read(probe, 0, probe.size, AudioRecord.READ_BLOCKING)
            partner.read(probe, 0, probe.size, AudioRecord.READ_BLOCKING)
            Thread.sleep(80L)

            val selfRouted = self.routedDevice
            val partnerRouted = partner.routedDevice
            val distinct = selfRouted != null && partnerRouted != null && selfRouted.id != partnerRouted.id
            val verified = self.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                partner.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                selfRouted?.id == base.earphoneInput.id &&
                partnerRouted?.id == base.deviceInput.id && distinct

            Log.i(
                TAG,
                "DUAL route check selfPreferred=${describe(base.earphoneInput)} selfRouted=${selfRouted?.let(::describe)} " +
                    "partnerPreferred=${describe(base.deviceInput)} partnerRouted=${partnerRouted?.let(::describe)} distinct=$distinct verified=$verified",
            )

            if (!verified) {
                releaseRecord(self)
                releaseRecord(partner)
                return@withContext base.copy(
                    message = "端末のAudio HALが2マイク分離を許可しません。従来の『話す』方式へ戻ります",
                )
            }

            selfRecord = self
            partnerRecord = partner
            selfAec = createAec(self, "SELF")
            partnerAec = createAec(partner, "PARTNER")
            running.set(true)
            selfThread = startPump(Channel.SELF, self)
            partnerThread = startPump(Channel.PARTNER, partner)

            base.copy(
                dualInputActive = true,
                message = "デュアル分離成功: イヤフォンMIC + デバイスMICを同時認識します",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "DUAL start failed", t)
            releaseRecord(self)
            releaseRecord(partner)
            base.copy(message = "デュアル音声分離を開始できません。従来モードへ戻ります")
        }
    }

    /** Returns a one-consumer PCM pipe. Closing the read side automatically removes the sink. */
    fun openRecognitionSource(channel: Channel): ParcelFileDescriptor? {
        if (!running.get()) return null
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val sink = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
        sinks(channel).add(sink)
        Log.d(TAG, "PCM subscriber added channel=$channel subscribers=${sinks(channel).size}")
        return readSide
    }

    fun isRunning(): Boolean = running.get()

    fun stop() {
        stopInternal("caller")
    }

    private fun stopInternal(reason: String) {
        if (running.getAndSet(false)) Log.i(TAG, "DUAL stop reason=$reason")
        closeSinks(selfSinks)
        closeSinks(partnerSinks)
        selfThread?.interrupt()
        partnerThread?.interrupt()
        selfThread = null
        partnerThread = null
        runCatching { selfAec?.release() }
        runCatching { partnerAec?.release() }
        selfAec = null
        partnerAec = null
        selfRecord?.let(::releaseRecord)
        partnerRecord?.let(::releaseRecord)
        selfRecord = null
        partnerRecord = null
    }

    private fun createRecord(device: AudioDeviceInfo, source: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        return runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min * 2, 16_384))
                .build()
                .also { record ->
                    val accepted = record.setPreferredDevice(device)
                    Log.i(TAG, "setPreferredDevice input=${describe(device)} accepted=$accepted session=${record.audioSessionId}")
                    if (!accepted || record.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { record.release() }
                        error("AudioRecord preferred route rejected for ${describe(device)}")
                    }
                }
        }.onFailure { Log.w(TAG, "createRecord failed device=${describe(device)}", it) }.getOrNull()
    }

    private fun startPump(channel: Channel, record: AudioRecord): Thread = Thread({
        val buffer = ByteArray(4096)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val count = try {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (t: Throwable) {
                Log.w(TAG, "PCM read failed channel=$channel", t)
                break
            }
            if (count <= 0) continue
            val current = sinks(channel)
            current.forEach { sink ->
                try {
                    sink.write(buffer, 0, count)
                } catch (_: IOException) {
                    current.remove(sink)
                    runCatching { sink.close() }
                } catch (t: Throwable) {
                    current.remove(sink)
                    runCatching { sink.close() }
                    Log.d(TAG, "PCM subscriber removed channel=$channel reason=${t.javaClass.simpleName}")
                }
            }
        }
        Log.i(TAG, "PCM pump end channel=$channel")
    }, "souyaku-dual-${channel.name.lowercase()}").apply {
        isDaemon = true
        start()
    }

    private fun createAec(record: AudioRecord, label: String): AcousticEchoCanceler? {
        if (!AcousticEchoCanceler.isAvailable()) return null
        return runCatching {
            AcousticEchoCanceler.create(record.audioSessionId)?.also {
                it.enabled = true
                Log.i(TAG, "AEC $label enabled=${it.enabled} session=${record.audioSessionId}")
            }
        }.onFailure { Log.w(TAG, "AEC $label unavailable", it) }.getOrNull()
    }

    private fun releaseRecord(record: AudioRecord) {
        runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        runCatching { record.release() }
    }

    private fun sinks(channel: Channel): CopyOnWriteArrayList<ParcelFileDescriptor.AutoCloseOutputStream> =
        if (channel == Channel.SELF) selfSinks else partnerSinks

    private fun closeSinks(list: CopyOnWriteArrayList<ParcelFileDescriptor.AutoCloseOutputStream>) {
        list.forEach { runCatching { it.close() } }
        list.clear()
    }

    private fun isEarphoneInput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> true
        else -> false
    }

    private fun isEarphoneOutput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
        else -> false
    }

    private fun describe(device: AudioDeviceInfo): String =
        "${device.productName}(type=${device.type},id=${device.id})"

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "SOUYAKU-DualAudio"
    }
}
