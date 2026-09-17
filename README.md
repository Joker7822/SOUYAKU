# SOUYAKU Interpreter 1.3.10

Android向けのリアルタイム相互通訳アプリです。1.3.0では、従来の対面通訳に加えて **トークン通訳通話** を追加しました。

## 1.3.0 の追加機能

- アプリ上部の「トークン通話」から専用画面へ移動
- 6〜12文字の英数字トークンで2台のSOUYAKUを同じルームへ接続
- 「作成」ボタンで共有用トークンを自動生成
- 1トークンあたり最大2台。3台目はサーバー側で拒否
- 接続後は会話ごとのボタン操作不要
- 各端末は **自分の言語だけ** を音声認識
- 認識した発話テキストと言語タグを相手へリアルタイム送信
- 相手端末で受信 → ML Kit翻訳 → Android TTSで自動読み上げ
- TTS再生と重なった音声認識結果を破棄し、通訳音声の送り返しループを防止
- AIキャラクター表示は中央配置のまま、LISTENING / TRANSLATING / SPEAKING / ERROR に連動

## 通信方式

この版の「通訳通話」は生音声ストリームをWebRTCで送る方式ではありません。

```
端末A
  マイク
    ↓
  SpeechRecognizer
    ↓ 発話テキスト + 言語タグ
  WebSocket
    ↓
トークン中継サーバー
    ↓
  WebSocket
    ↓
端末B
  ML Kit Translation
    ↓
  Android TTS
    ↓
  スピーカー
```

逆方向も同じ経路で同時に動きます。

この方式にした理由は、AndroidでWebRTCの音声キャプチャとSpeechRecognizerを同時に動かすとマイク入力が競合しやすいためです。生音声はサーバーへ送らず、通訳に必要な発話テキストだけを中継します。

## サーバー起動

`server/` にNode.js製の中継サーバーを同梱しています。

```bash
cd server
npm install
npm start
```

既定ポートは `8787` です。

疎通確認:

```bash
curl http://127.0.0.1:8787/health
```

### 既定の接続先

アプリの初期接続先は次です。

```text
wss://souyaku-token-relay.onrender.com
```

ローカル開発時だけ、接続サーバー欄で `ws://10.0.2.2:8787` などへ手動変更できます。

### Android実機2台

同じLANで検証する場合は、サーバーPCのLAN IPを指定します。

```text
ws://192.168.x.x:8787
```

本番公開では **必ずTLSを終端し `wss://` を使用してください**。Caddy / nginx / Cloud Load BalancerなどでTLSを終端し、Node.jsサーバーへリバースプロキシしてください。

## トークン通話の手順

1. 端末Aで「トークン通話」を開く
2. 右上の「設定」を開き、「作成」でトークンを生成
3. 「共有」またはメイン画面の「トークンを共有」を押す
4. Android共有シートからLINE・メール・SMSなどで、トークンとWSS接続先を相手へ送る
5. 端末Bは受け取ったトークンを設定画面へ入力
6. 両端末で自分の言語（固定またはAUTO）を設定し、メイン画面の「接続」を押す
7. 接続台数が `2 / 2` になれば、そのまま会話開始

例:

- A: 自分の言語 = 日本語
- B: 自分の言語 = English
- Aが「こんにちは」と話す
- Bには英語字幕と英語TTSが出力
- Bが英語で返答
- Aには日本語字幕と日本語TTSが出力

## セキュリティ上の注意

- トークンは接続ルームIDです。ユーザー認証トークンではありません。
- 1ルームは最大2台ですが、トークンを知っている第三者が先に接続する可能性はあります。
- 公開サービスではWSSに加えて、短時間で期限切れになるサーバー署名付き招待トークン、レート制限、接続ログの最小化を推奨します。
- 現在の中継サーバーは会話を保存しませんが、平文の発話テキストを中継時に処理します。サーバー運営者からも内容を隠すE2EEは未実装です。

## バージョン

- versionName: `1.3.10`
- versionCode: `15`
- applicationId: `com.epic.souyaku.interpreter`
- minSdk: 26
- targetSdk: 36

## 依存関係

- AndroidX / Jetpack Compose
- Google ML Kit Translate 17.0.3
- Google ML Kit Language ID 17.0.6
- OkHttp 5.3.2 (Android WebSocket; compileSdk 36 compatible)
- server: ws 8.21.3

## ビルド検証について

この作業環境にはAndroid SDK / Gradle Wrapperが無いため、APKの最終コンパイルは未確認です。Kotlin/Nodeソースの構文・ファイル整合性とZIP整合性は別途確認します。実機では2台接続、マイク認識、翻訳モデル、TTS、ネットワーク切断復帰を確認してください。

## 1.3.1 Render WSS deployment

- Added root `render.yaml` for Render Blueprint deployment.
- Server binds to Render's `PORT` on `0.0.0.0` and exposes `/health`.
- Android default token-call server can be supplied at build time with `SOUYAKU_TOKEN_CALL_SERVER_URL`.
- For public Render connections, use the generated `wss://...onrender.com` URL.
- See `RENDER_DEPLOY.md` for deployment and two-device test steps.


## 1.3.2 build compatibility fix

- Fixed Android build failure caused by OkHttp 5.5.0 requiring compileSdk 37.
- Pinned OkHttp to 5.3.2 so the project can remain on compileSdk / targetSdk 36 with AGP 8.13.2.


## 1.3.3 default Render WSS endpoint

The token-call screen now defaults to `wss://souyaku-token-relay.onrender.com`.
Legacy development defaults such as `ws://10.0.2.2:8787` are migrated automatically on upgrade, while a user-entered custom WSS endpoint is preserved.


## 1.3.4 token connection settings UI

- Moved token connection controls into the token-call settings sub-screen.
- The live token-call screen now focuses on connection status, the centered character, captions, and translation state.
- Open `設定` from the token-call screen to manage the WSS endpoint, token, own language, connect, and disconnect.
- The WebSocket session remains active when switching between the live call view and its settings sub-screen.
- The token is persisted locally so it can be reused after reopening the screen.


## 1.3.5 UI cleanup

- Removed the redundant top summary card showing `相手 ... ⇄ 自分 ...` from the face-to-face interpreter screen.
- Kept the interactive `相手` / `自分` language selectors directly below for quick language changes.
- Token connection settings remain in the settings screen from 1.3.4.


## 1.3.6 token-call UI / address book

- Moved the token-call connect/disconnect button back to the main token-call screen.
- Token-call settings now manage the WSS server URL, connection token, and the user's spoken language.
- Added a local token address book: save a display name + token, reuse it with one tap, or delete it.
- Address book entries are stored only in Android SharedPreferences and are not sent to the relay server.
- Address book capacity: up to 50 entries. Existing names or tokens are updated instead of duplicated.
- versionName: `1.3.6`
- versionCode: `11`


## 1.3.7 ALL AUTO mode

- Added `AUTO ⇄ AUTO` mode to face-to-face interpretation. Both speakers' languages are detected from speech/text instead of requiring a fixed self language.
- The first detected language becomes language A; the next different detected language becomes language B. Translation starts once both sides are known and then switches direction automatically per utterance.
- Added `AUTO（自動抽出）` to the token-call own-language setting. Local utterances are language-identified before their language tag is sent to the peer.
- The last locally detected token-call language is stored on-device and reused as the translation target on later AUTO calls. It can be reset from settings.
- When both token-call devices are brand new in AUTO and neither has a cached local language, each device must speak once before that device can know which language incoming speech should be translated into.
- Android 14 / API 34+ multilingual SpeechRecognizer language detection/switching is preferred; ML Kit text Language ID is used as an additional detector.
- Preserves 1.3.6: main-screen connect/disconnect button, editable own language, and local token address book.
- versionName: `1.3.7`
- versionCode: `12`

## 1.3.8 voice-triggered initial AUTO extraction

- In ALL AUTO, the first language extraction no longer starts from arbitrary speech.
- Say **「接続」** first to arm initial AUTO extraction.
- The word 「接続」 is treated only as a local voice command; it is not used as a language sample and is not sent as conversation text.
- The next natural utterance is used to identify the local language.
- Face-to-face ALL AUTO then waits for a different second language and forms the bidirectional pair automatically.
- Token call AUTO uses the same flow on each device: connect button -> say 「接続」 -> speak one natural sentence -> language fixed automatically.
- Existing saved AUTO language remains reusable until the user resets AUTO detection.
- Preserves main-screen connect/disconnect, editable own language, token address book, Render WSS default, and OkHttp 5.3.2 build compatibility.
- versionName: `1.3.8`
- versionCode: `13`


## 1.3.9 outbound translation fix

- Fixed token calls where the sender only relayed source text and did not translate into the peer language on the sender side.
- Added peer-language synchronization (`peer_language` / `language_update`).
- AUTO language detection now publishes the detected language immediately after detection.
- Outgoing utterances can carry `targetLanguage` and `translatedText`; the receiver uses the pretranslated text when it matches its local language, otherwise it falls back to local ML Kit translation.


## 1.3.10 token sharing simplification

- Removed the token address book UI and all address-book persistence APIs.
- Legacy `token_contacts` data is deleted from SharedPreferences when settings are initialized.
- Added a main-screen `トークンを共有` button.
- Added `共有` and `コピー` actions next to token settings.
- Android Sharesheet sends both the room token and current WSS server URL, so users can share via LINE, email, SMS, etc.
- Token copy uses the Android clipboard for quick paste.
- versionName: `1.3.10`
- versionCode: `15`
