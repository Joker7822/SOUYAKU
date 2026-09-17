package com.epic.souyaku

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SouyakuTokenCallScreen(onBackToInterpreter: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SouyakuSettingsStore(context) }
    val speech = remember { OnDeviceSpeechEngine(context) }
    val tts = remember { AndroidLocalTts(context) }
    val translator = remember { SouyakuTranslationPool() }
    val client = remember { SouyakuTokenCallClient() }
    val remoteSpeechMutex = remember { Mutex() }
    val remotePlaybackGeneration = remember { AtomicLong(0L) }
    val suppressOutgoingUntil = remember { AtomicLong(0L) }

    var serverUrl by remember { mutableStateOf(settings.tokenCallServerUrl()) }
    var token by remember { mutableStateOf(SouyakuTokenCallClient.generateToken()) }
    var selfLanguage by remember { mutableStateOf(InterpreterLanguage.fromSpeechTag(settings.tokenCallLanguage())) }
    var languageMenu by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(SouyakuTokenCallClient.State.DISCONNECTED) }
    var peerCount by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("トークンを相手と共有して接続してください。") }
    var phase by remember { mutableStateOf(InterpreterPhase.IDLE) }
    var listenJob by remember { mutableStateOf<Job?>(null) }
    var pendingConnect by remember { mutableStateOf(false) }
    var originalText by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var remoteLanguage by remember { mutableStateOf<InterpreterLanguage?>(null) }

    fun stopListeningLoop() {
        listenJob?.cancel()
        listenJob = null
    }

    fun startListeningLoop() {
        if (listenJob?.isActive == true || state != SouyakuTokenCallClient.State.CONNECTED || peerCount < 2) return
        listenJob = scope.launch {
            phase = InterpreterPhase.LISTENING
            var failureStreak = 0
            while (isActive && state == SouyakuTokenCallClient.State.CONNECTED && peerCount >= 2) {
                val generationAtStart = remotePlaybackGeneration.get()
                try {
                    phase = InterpreterPhase.LISTENING
                    status = "接続中：話してください"
                    val result = speech.recognizeOnceDetailed(
                        language = selfLanguage.speechTag,
                        timeoutMillis = 12_000L,
                        allowSystemFallback = settings.internetEnabled(),
                        autoDetectLanguages = emptyList(),
                        preferOnDevice = true,
                        onPartial = { partial ->
                            if (remotePlaybackGeneration.get() == generationAtStart &&
                                SystemClock.elapsedRealtime() >= suppressOutgoingUntil.get()
                            ) {
                                remoteLanguage = null
                                originalText = partial
                                translatedText = ""
                            }
                        },
                    )
                    val heard = result.text.trim()
                    if (heard.isBlank()) {
                        delay(120)
                        continue
                    }
                    failureStreak = 0

                    if (remotePlaybackGeneration.get() != generationAtStart ||
                        SystemClock.elapsedRealtime() < suppressOutgoingUntil.get()
                    ) {
                        status = "相手の通訳音声を再認識しないよう入力を破棄しました。"
                        delay(120)
                        continue
                    }

                    remoteLanguage = null
                    originalText = heard
                    translatedText = ""
                    phase = InterpreterPhase.TRANSLATING
                    status = "相手へ送信中…"
                    val sent = client.sendUtterance(heard, selfLanguage.speechTag)
                    phase = InterpreterPhase.LISTENING
                    status = if (sent) "送信済み。次の発話を待っています" else "送信できませんでした。接続を確認してください。"
                } catch (e: TimeoutCancellationException) {
                    failureStreak = (failureStreak + 1).coerceAtMost(4)
                    phase = InterpreterPhase.LISTENING
                    status = "接続中：話してください"
                    delay((200L * failureStreak).coerceAtMost(800L))
                } catch (e: SpeechRecognitionException) {
                    failureStreak = (failureStreak + 1).coerceAtMost(4)
                    phase = InterpreterPhase.LISTENING
                    status = when (e.errorCode) {
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                            "${selfLanguage.displayName}の音声認識モデルを利用できません。"
                        else -> "音声認識へ再接続しています…"
                    }
                    delay((350L * failureStreak).coerceAtMost(1_200L))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failureStreak = (failureStreak + 1).coerceAtMost(4)
                    phase = InterpreterPhase.ERROR
                    status = "音声認識エラー: ${e.message ?: "unknown"}"
                    delay(600L)
                }
            }
        }
    }

    fun handleRemoteUtterance(utterance: SouyakuTokenCallClient.RemoteUtterance) {
        remotePlaybackGeneration.incrementAndGet()
        scope.launch {
            remoteSpeechMutex.withLock {
                val sourceLanguage = InterpreterLanguage.fromDetectedTag(utterance.sourceLanguageTag)
                    ?: InterpreterLanguage.spokenEntries.firstOrNull {
                        it.speechTag.equals(utterance.sourceLanguageTag, ignoreCase = true)
                    }
                if (sourceLanguage == null) {
                    status = "相手の言語 ${utterance.sourceLanguageTag} に対応していません。"
                    phase = InterpreterPhase.ERROR
                    return@withLock
                }

                remoteLanguage = sourceLanguage
                phase = InterpreterPhase.TRANSLATING
                status = "${sourceLanguage.displayName} → ${selfLanguage.displayName} 翻訳中…"
                originalText = utterance.text
                val translated = if (sourceLanguage == selfLanguage) {
                    utterance.text
                } else {
                    translator.prepare(sourceLanguage, selfLanguage)
                    translator.translate(sourceLanguage, selfLanguage, utterance.text)
                }
                translatedText = translated
                if (translated.isBlank()) {
                    status = "相手の発話を翻訳できませんでした。"
                    phase = InterpreterPhase.ERROR
                    return@withLock
                }

                remotePlaybackGeneration.incrementAndGet()
                phase = InterpreterPhase.SPEAKING
                status = "${selfLanguage.displayName}で相手の発話を通訳しています"
                val spoken = tts.speak(
                    text = translated,
                    languageTag = selfLanguage.speechTag,
                    requireOffline = !settings.internetEnabled(),
                )
                suppressOutgoingUntil.set(SystemClock.elapsedRealtime() + 700L)
                phase = InterpreterPhase.LISTENING
                status = if (spoken) "接続中：話してください" else "翻訳字幕を表示しました。TTS音声を利用できません。"
            }
        }
    }

    fun connectNow() {
        val cleanToken = SouyakuTokenCallClient.normalizeToken(token)
        token = cleanToken
        settings.setTokenCallServerUrl(serverUrl.trim())
        settings.setTokenCallLanguage(selfLanguage.speechTag)
        client.connect(
            serverUrl = serverUrl.trim(),
            token = cleanToken,
            languageTag = selfLanguage.speechTag,
            listener = object : SouyakuTokenCallClient.Listener {
                override fun onStateChanged(newState: SouyakuTokenCallClient.State, message: String) {
                    state = newState
                    status = message
                    if (newState == SouyakuTokenCallClient.State.DISCONNECTED || newState == SouyakuTokenCallClient.State.ERROR) {
                        peerCount = 0
                        stopListeningLoop()
                        if (newState == SouyakuTokenCallClient.State.ERROR) phase = InterpreterPhase.ERROR
                        else phase = InterpreterPhase.IDLE
                    }
                }

                override fun onPeerCountChanged(count: Int) {
                    peerCount = count
                    if (count >= 2 && state == SouyakuTokenCallClient.State.CONNECTED) {
                        phase = InterpreterPhase.LISTENING
                        status = "相手と接続しました。話してください"
                        startListeningLoop()
                    } else if (count < 2) {
                        stopListeningLoop()
                        phase = InterpreterPhase.IDLE
                        if (state == SouyakuTokenCallClient.State.CONNECTED) status = "接続済み。相手を待っています…"
                    }
                }

                override fun onRemoteUtterance(utterance: SouyakuTokenCallClient.RemoteUtterance) {
                    handleRemoteUtterance(utterance)
                }
            },
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingConnect) connectNow()
        else if (!granted) {
            state = SouyakuTokenCallClient.State.ERROR
            phase = InterpreterPhase.ERROR
            status = "通訳通話にはマイク権限が必要です。"
        }
        pendingConnect = false
    }

    fun requestConnect() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            connectNow()
        } else {
            pendingConnect = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopListeningLoop()
            client.close()
            translator.close()
            tts.shutdown()
        }
    }

    val visualState = when (phase) {
        InterpreterPhase.IDLE -> SouyakuVisualState.IDLE
        InterpreterPhase.PREPARING, InterpreterPhase.DETECTING, InterpreterPhase.TRANSLATING -> SouyakuVisualState.THINKING
        InterpreterPhase.LISTENING -> SouyakuVisualState.LISTENING
        InterpreterPhase.SPEAKING -> SouyakuVisualState.SPEAKING
        InterpreterPhase.ERROR -> SouyakuVisualState.ERROR
    }

    SouyakuTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SOUYAKU 通訳通話") },
                    actions = {
                        TextButton(onClick = onBackToInterpreter) { Text("対面通訳") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("トークンで接続", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = SouyakuTokenCallClient.normalizeToken(it) },
                                label = { Text("接続トークン") },
                                singleLine = true,
                                enabled = state == SouyakuTokenCallClient.State.DISCONNECTED || state == SouyakuTokenCallClient.State.ERROR,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { token = SouyakuTokenCallClient.generateToken() },
                                enabled = state == SouyakuTokenCallClient.State.DISCONNECTED || state == SouyakuTokenCallClient.State.ERROR,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            ) { Text("作成") }
                        }
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("接続サーバー") },
                            supportingText = { Text("本番環境は wss:// を使用") },
                            singleLine = true,
                            enabled = state == SouyakuTokenCallClient.State.DISCONNECTED || state == SouyakuTokenCallClient.State.ERROR,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { languageMenu = true },
                                enabled = state == SouyakuTokenCallClient.State.DISCONNECTED || state == SouyakuTokenCallClient.State.ERROR,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("自分の言語: ${selfLanguage.displayName}") }
                            DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                                InterpreterLanguage.spokenEntries.forEach { language ->
                                    DropdownMenuItem(
                                        text = { Text(language.displayName) },
                                        onClick = {
                                            selfLanguage = language
                                            settings.setTokenCallLanguage(language.speechTag)
                                            languageMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        if (state == SouyakuTokenCallClient.State.CONNECTED || state == SouyakuTokenCallClient.State.CONNECTING) {
                            Button(
                                onClick = {
                                    stopListeningLoop()
                                    client.disconnect()
                                    peerCount = 0
                                    state = SouyakuTokenCallClient.State.DISCONNECTED
                                    phase = InterpreterPhase.IDLE
                                    status = "切断しました。"
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("切断") }
                        } else {
                            Button(onClick = { requestConnect() }, modifier = Modifier.fillMaxWidth()) {
                                Text("トークンで接続")
                            }
                        }

                        Text(
                            "接続台数: $peerCount / 2",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(vertical = 18.dp, horizontal = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(210.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            SouyakuParticleCharacter(
                                state = visualState,
                                modifier = Modifier.align(Alignment.Center),
                                size = 190.dp,
                                showLabel = false,
                            )
                        }
                        Text(
                            when (phase) {
                                InterpreterPhase.LISTENING -> "🎙  CONNECTED / LISTENING"
                                InterpreterPhase.TRANSLATING, InterpreterPhase.DETECTING -> "⇄  TRANSLATING"
                                InterpreterPhase.SPEAKING -> "🔊  INTERPRETING"
                                InterpreterPhase.PREPARING -> "…  PREPARING"
                                InterpreterPhase.ERROR -> "!  ERROR"
                                InterpreterPhase.IDLE -> "⇄  WAITING"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(status, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    }
                }

                if (originalText.isNotBlank() || translatedText.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                remoteLanguage?.let { "相手: ${it.displayName}" } ?: "自分: ${selfLanguage.displayName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(originalText, style = MaterialTheme.typography.titleMedium)
                            if (translatedText.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text("通訳: ${selfLanguage.displayName}", style = MaterialTheme.typography.labelMedium)
                                Text(translatedText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "この通訳通話は、生音声そのものではなく認識した発話テキストをトークンルームへ送り、相手端末で翻訳してTTS音声にします。音声データを中継しないため既存の音声認識と競合しにくく、通信量も小さくできます。同時発話時は自己TTSの再送を防ぐため、TTSと重なった認識結果を破棄します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
