package com.epic.souyaku

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class SouyakuTokenCallClient {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class RemoteUtterance(
        val senderId: String,
        val sequence: Long,
        val sourceLanguageTag: String,
        val text: String,
    )

    interface Listener {
        fun onStateChanged(state: State, message: String)
        fun onPeerCountChanged(peerCount: Int)
        fun onRemoteUtterance(utterance: RemoteUtterance)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var listener: Listener? = null
    private var roomToken: String = ""
    private val clientId: String = UUID.randomUUID().toString()
    private var sequence = 0L

    fun connect(serverUrl: String, token: String, languageTag: String, listener: Listener) {
        disconnect("reconnect")
        this.listener = listener
        roomToken = normalizeToken(token)
        if (roomToken.length !in 6..12) {
            post { listener.onStateChanged(State.ERROR, "トークンは6〜12文字の英数字にしてください。") }
            return
        }
        val normalizedUrl = serverUrl.trim()
        if (!(normalizedUrl.startsWith("ws://") || normalizedUrl.startsWith("wss://"))) {
            post { listener.onStateChanged(State.ERROR, "サーバーURLは ws:// または wss:// で指定してください。") }
            return
        }

        post { listener.onStateChanged(State.CONNECTING, "接続中…") }
        val request = Request.Builder().url(normalizedUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val join = JSONObject()
                    .put("type", "join")
                    .put("token", roomToken)
                    .put("clientId", clientId)
                    .put("language", languageTag)
                webSocket.send(join.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(JSONObject(text)) }
                    .onFailure { error -> post { listener.onStateChanged(State.ERROR, "受信データを解析できません: ${error.message}") } }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                post {
                    listener.onPeerCountChanged(0)
                    listener.onStateChanged(State.DISCONNECTED, if (reason.isBlank()) "切断しました。" else reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                post {
                    listener.onPeerCountChanged(0)
                    listener.onStateChanged(State.ERROR, "接続エラー: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        })
    }

    fun sendUtterance(text: String, sourceLanguageTag: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        sequence += 1
        val payload = JSONObject()
            .put("type", "utterance")
            .put("token", roomToken)
            .put("clientId", clientId)
            .put("sequence", sequence)
            .put("sourceLanguage", sourceLanguageTag)
            .put("text", clean.take(4000))
        return socket?.send(payload.toString()) == true
    }

    fun disconnect(reason: String = "ユーザーが切断しました。") {
        val current = socket
        socket = null
        current?.close(1000, reason.take(120))
    }

    fun close() {
        disconnect("screen disposed")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        listener = null
    }

    private fun handleMessage(json: JSONObject) {
        val current = listener ?: return
        when (json.optString("type")) {
            "joined" -> {
                val peers = json.optInt("peerCount", 1)
                post {
                    current.onStateChanged(State.CONNECTED, if (peers >= 2) "相手と接続しました。" else "接続済み。相手を待っています…")
                    current.onPeerCountChanged(peers)
                }
            }
            "peer_count" -> {
                val peers = json.optInt("peerCount", 1)
                post {
                    current.onPeerCountChanged(peers)
                    current.onStateChanged(State.CONNECTED, if (peers >= 2) "相手と接続しました。" else "接続済み。相手を待っています…")
                }
            }
            "utterance" -> {
                val sender = json.optString("senderId")
                if (sender == clientId) return
                val utterance = RemoteUtterance(
                    senderId = sender,
                    sequence = json.optLong("sequence", 0L),
                    sourceLanguageTag = json.optString("sourceLanguage"),
                    text = json.optString("text").take(4000),
                )
                if (utterance.text.isNotBlank()) post { current.onRemoteUtterance(utterance) }
            }
            "error" -> {
                val code = json.optString("code")
                val message = when (code) {
                    "room_full" -> "このトークンにはすでに2台接続されています。"
                    "invalid_token" -> "トークンが無効です。"
                    "join_required" -> "サーバーとの参加処理に失敗しました。"
                    else -> json.optString("message", "サーバーエラー")
                }
                post { current.onStateChanged(State.ERROR, message) }
            }
        }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        private const val TOKEN_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        fun generateToken(length: Int = 8): String = buildString {
            repeat(length.coerceIn(6, 12)) {
                append(TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)])
            }
        }

        fun normalizeToken(value: String): String = value
            .uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .take(12)
    }
}
