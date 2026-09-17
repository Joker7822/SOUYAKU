package com.epic.souyaku

import android.content.Context

/** Preferences used only by the bidirectional interpreter. */
class SouyakuSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Online fallback is useful for Android speech recognition and initial model downloads. */
    fun internetEnabled(): Boolean = prefs.getBoolean(KEY_INTERNET_ENABLED, true)
    fun setInternetEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_INTERNET_ENABLED, value).apply()

    fun interpreterParallelismEnabled(): Boolean = prefs.getBoolean(KEY_INTERPRETER_PARALLELISM, true)
    fun setInterpreterParallelismEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_INTERPRETER_PARALLELISM, value).apply()

    fun interpreterSource(): String = prefs.getString(KEY_INTERPRETER_SOURCE, "auto") ?: "auto"
    fun setInterpreterSource(value: String) = prefs.edit().putString(KEY_INTERPRETER_SOURCE, value).apply()

    fun interpreterTarget(): String = prefs.getString(KEY_INTERPRETER_TARGET, "ja-JP") ?: "ja-JP"
    fun setInterpreterTarget(value: String) = prefs.edit().putString(KEY_INTERPRETER_TARGET, value).apply()

    fun tokenCallServerUrl(): String = prefs.getString(KEY_TOKEN_CALL_SERVER_URL, BuildConfig.TOKEN_CALL_SERVER_URL)
        ?: BuildConfig.TOKEN_CALL_SERVER_URL
    fun setTokenCallServerUrl(value: String) = prefs.edit().putString(KEY_TOKEN_CALL_SERVER_URL, value).apply()

    fun tokenCallLanguage(): String = prefs.getString(KEY_TOKEN_CALL_LANGUAGE, interpreterTarget()) ?: interpreterTarget()
    fun setTokenCallLanguage(value: String) = prefs.edit().putString(KEY_TOKEN_CALL_LANGUAGE, value).apply()

    companion object {
        private const val PREFS = "souyaku_interpreter_settings"
        private const val KEY_INTERNET_ENABLED = "internet_enabled"
        private const val KEY_INTERPRETER_PARALLELISM = "interpreter_parallelism"
        private const val KEY_INTERPRETER_SOURCE = "interpreter_source"
        private const val KEY_INTERPRETER_TARGET = "interpreter_target"
        private const val KEY_TOKEN_CALL_SERVER_URL = "token_call_server_url"
        private const val KEY_TOKEN_CALL_LANGUAGE = "token_call_language"
    }
}
