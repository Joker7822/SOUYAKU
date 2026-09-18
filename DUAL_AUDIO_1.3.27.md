# SOUYAKU 1.3.27 Dual Audio integration

SOUYAKU 1.3.27 adds a capability-tested dual-audio path for face-to-face interpretation.

## Target routing

- Earphone/headset microphone -> SELF speech recognizer -> translate own language to partner language -> built-in speaker.
- Built-in microphone -> PARTNER speech recognizer -> translate partner language to own language -> earphone/headset output.

## Runtime architecture

1. `SouyakuDualAudioEngine` enumerates the four required audio endpoints.
2. It opens two concurrent `AudioRecord` streams at 16 kHz mono PCM16 and requests the earphone mic and built-in mic independently with `setPreferredDevice()`.
3. Recording starts before routing is accepted as valid. `routedDevice` is checked for both streams; Dual Audio is enabled only when the actual routed device IDs match the requested devices and are distinct.
4. Each capture stream is fanned out to an independent `ParcelFileDescriptor` PCM pipe.
5. Face-to-face mode uses two separate `OnDeviceSpeechEngine` instances so SELF and PARTNER recognition do not cancel one another.
6. Translation and TTS are child jobs so capture/recognition can continue while output is being produced.
7. `AndroidLocalTts.speakToDevice()` synthesizes speech to a temporary audio file and uses `MediaPlayer.setPreferredDevice()` for direction-specific output routing.
8. When available, `AcousticEchoCanceler` is enabled on both capture sessions.

## Fallback

Android/OEM Audio Policy remains authoritative. If either preferred input is rejected, both records route to the same device, or concurrent recognition fails non-recoverably, the session falls back to the 1.3.26 turn-based earphone mode using the existing `話す` button.

True Dual Audio requires Android 13+ because the recognizers consume external PCM via `RecognizerIntent.EXTRA_AUDIO_SOURCE`.

## Version

- versionName: `1.3.27`
- versionCode: `32`

## Distribution artifact

`SOUYAKU_Interpreter_1.3.27_dual_audio.zip`

SHA-256: `e98006b17adab986a9ca9c86cc959eafb2ab3e2f43665a10af19906d65f9f376`

The full integrated project archive is produced from the same 1.3.27 source snapshot. APK compilation is not claimed here because the generation environment does not include an Android SDK/Gradle wrapper.
