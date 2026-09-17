# SOUYAKU Interpreter 1.3.1

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

### エミュレーター

アプリの既定URLは次です。

```text
ws://10.0.2.2:8787
```

### Android実機2台

同じLANで検証する場合は、サーバーPCのLAN IPを指定します。

```text
ws://192.168.x.x:8787
```

本番公開では **必ずTLSを終端し `wss://` を使用してください**。Caddy / nginx / Cloud Load BalancerなどでTLSを終端し、Node.jsサーバーへリバースプロキシしてください。

## トークン通話の手順

1. 端末Aで「トークン通話」を開く
2. 「作成」でトークンを生成
3. 同じサーバーURLとトークンを端末Bへ共有
4. 両端末で自分の言語を選択
5. 両端末で「トークンで接続」
6. 接続台数が `2 / 2` になれば、そのまま会話開始

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

- versionName: `1.3.1`
- versionCode: `6`
- applicationId: `com.epic.souyaku.interpreter`
- minSdk: 26
- targetSdk: 36

## 依存関係

- AndroidX / Jetpack Compose
- Google ML Kit Translate 17.0.3
- Google ML Kit Language ID 17.0.6
- OkHttp 5.5.0 (Android WebSocket)
- server: ws 8.21.3

## ビルド検証について

この作業環境にはAndroid SDK / Gradle Wrapperが無いため、APKの最終コンパイルは未確認です。Kotlin/Nodeソースの構文・ファイル整合性とZIP整合性は別途確認します。実機では2台接続、マイク認識、翻訳モデル、TTS、ネットワーク切断復帰を確認してください。

## 1.3.1 Render WSS deployment

- Added root `render.yaml` for Render Blueprint deployment.
- Server binds to Render's `PORT` on `0.0.0.0` and exposes `/health`.
- Android default token-call server can be supplied at build time with `SOUYAKU_TOKEN_CALL_SERVER_URL`.
- For public Render connections, use the generated `wss://...onrender.com` URL.
- See `RENDER_DEPLOY.md` for deployment and two-device test steps.
