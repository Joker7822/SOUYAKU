package com.epic.souyaku

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Best-effort face-to-face audio routing for headset-assisted interpretation.
 *
 * Android pairs communication output with a corresponding input. We therefore switch the
 * communication route before each recognition/TTS phase instead of trying to keep two capture
 * devices open simultaneously.
 */
class SouyakuAudioRouter(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    data class RouteState(
        val supported: Boolean,
        val earphoneConnected: Boolean,
        val earphoneName: String? = null,
        val message: String,
    )

    fun state(): RouteState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return RouteState(false, detectEarphoneOutput() != null, detectEarphoneOutput()?.productName?.toString(), "Android 12以降で音声ルート分離に対応")
        }
        val ear = findCommunicationEarphone()
        return RouteState(
            supported = true,
            earphoneConnected = ear != null,
            earphoneName = ear?.productName?.toString(),
            message = if (ear != null) "イヤフォン対面通訳を利用できます" else "対応イヤフォンが見つかりません",
        )
    }

    /** Partner listening: route communication to built-in speaker so Android pairs built-in mic. */
    fun routePartnerInputToDeviceMic(): Boolean = setCommunicationDeviceOfType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "PARTNER_DEVICE_MIC")

    /** Self listening: selecting headset output causes Android to pair the headset microphone. */
    fun routeSelfInputToEarphoneMic(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = findCommunicationEarphone() ?: return false
        return setCommunicationDevice(device, "SELF_EARPHONE_MIC")
    }

    /** Partner -> self: translated voice should be private in the earphone. */
    fun routePartnerTranslationToEarphone(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = findCommunicationEarphone() ?: return false
        return setCommunicationDevice(device, "PARTNER_TO_SELF_EARPHONE")
    }

    /** Self -> partner: translated voice should be audible from the phone speaker. */
    fun routeSelfTranslationToSpeaker(): Boolean = setCommunicationDeviceOfType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SELF_TO_PARTNER_SPEAKER")

    fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
                .onFailure { Log.w(TAG, "clearCommunicationDevice failed", it) }
        }
    }

    fun ttsCommunicationAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun setCommunicationDeviceOfType(type: Int, label: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type } ?: return false
        return setCommunicationDevice(device, label)
    }

    private fun setCommunicationDevice(device: AudioDeviceInfo, label: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val accepted = runCatching { audioManager.setCommunicationDevice(device) }.getOrDefault(false)
        val routed = audioManager.communicationDevice
        val verified = accepted && routed?.id == device.id
        Log.i(TAG, "$label requested=${describe(device)} accepted=$accepted routed=${routed?.let(::describe)} verified=$verified")
        return verified
    }

    private fun findCommunicationEarphone(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return audioManager.availableCommunicationDevices.firstOrNull { isEarphone(it) }
    }

    fun earphoneOutputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isEarphone(it) }

    fun speakerOutputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun detectEarphoneOutput(): AudioDeviceInfo? = earphoneOutputDevice()

    private fun isEarphone(device: AudioDeviceInfo): Boolean = when (device.type) {
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

    private fun describe(device: AudioDeviceInfo): String = "${device.productName}(type=${device.type},id=${device.id})"

    companion object { private const val TAG = "SOUYAKU-AudioRoute" }
}
