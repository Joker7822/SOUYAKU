package com.epic.souyaku

import java.util.Locale

/** Languages exposed by SOUYAKU's low-latency on-device interpreter UI. */
enum class InterpreterLanguage(
    val displayName: String,
    val speechTag: String,
    val translateTag: String,
    val locale: Locale,
    val isAuto: Boolean = false,
) {
    AUTO("AUTO", "auto", "", Locale.ROOT, true),
    JAPANESE("日本語", "ja-JP", "ja", Locale.JAPAN),
    ENGLISH("English", "en-US", "en", Locale.US),
    CHINESE("中文", "zh-CN", "zh", Locale.SIMPLIFIED_CHINESE),
    KOREAN("한국어", "ko-KR", "ko", Locale.KOREA),
    SPANISH("Español", "es-ES", "es", Locale("es", "ES")),
    FRENCH("Français", "fr-FR", "fr", Locale.FRANCE),
    GERMAN("Deutsch", "de-DE", "de", Locale.GERMANY),
    ITALIAN("Italiano", "it-IT", "it", Locale.ITALY),
    PORTUGUESE("Português", "pt-BR", "pt", Locale("pt", "BR")),
    THAI("ไทย", "th-TH", "th", Locale("th", "TH")),
    VIETNAMESE("Tiếng Việt", "vi-VN", "vi", Locale("vi", "VN")),
    INDONESIAN("Bahasa Indonesia", "id-ID", "id", Locale("id", "ID"));

    companion object {
        val spokenEntries: List<InterpreterLanguage> get() = entries.filterNot { it.isAuto }

        fun fromSpeechTag(tag: String?): InterpreterLanguage {
            if (tag.equals("auto", ignoreCase = true)) return AUTO
            return entries.firstOrNull { it.speechTag.equals(tag, ignoreCase = true) } ?: JAPANESE
        }

        fun fromDetectedTag(tag: String?): InterpreterLanguage? {
            val normalized = tag.orEmpty().trim().lowercase(Locale.ROOT)
            if (normalized.isBlank() || normalized == "und") return null
            val base = normalized.substringBefore('-').substringBefore('_')
            return spokenEntries.firstOrNull {
                it.translateTag.lowercase(Locale.ROOT) == base ||
                    it.speechTag.lowercase(Locale.ROOT) == normalized
            }
        }
    }
}
