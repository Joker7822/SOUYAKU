package com.epic.souyaku

import android.content.Context
import android.media.AudioManager

/**
 * Detects whether Android currently reports an active telephony/VoIP communication mode.
 *
 * This intentionally does NOT attempt to capture call downlink/uplink audio. Android only
 * exposes direct voice-call capture to privileged/preinstalled apps with protected permissions.
 */
class SouyakuCallModeMonitor(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    data class State(
        val active: Boolean,
        val mode: Int,
        val modeLabel: String,
    )

    fun read(): State {
        val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val active = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        return State(active = active, mode = mode, modeLabel = label(mode))
    }

    private fun label(mode: Int): String = when (mode) {
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        else -> "MODE_$mode"
    }
}
