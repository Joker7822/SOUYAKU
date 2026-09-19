# SOUYAKU Interpreter 1.3.33

## SOUYAKU Agent

SOUYAKU独自の会話支援AIエージェントを追加しました。既存のSpeechRecognizer / ML Kit / Android TTS通訳パイプラインとは独立して動作し、AI側が利用できない場合も通常の通訳機能は継続できます。

### Android

- 設定画面へ `SOUYAKU Agent` を追加。初期値はOFF。
- 会話モード: 自動 / 日常会話 / 旅行 / ホテル / 飲食店 / 買い物 / ビジネス。
- 対面通訳とトークン通話の両方で、直近16件の通訳済み会話を端末RAM上だけに保持。
- ユーザーが明示的にAIボタンを押したときだけ会話テキストを送信。
- AI操作: `返答候補` / `意味を説明` / `会話を要約` / `曖昧さ確認`。
- 返答候補は自分の言語と相手言語の2表記を返し、相手言語文をクリップボードへコピー可能。
- AIは自動発話、自動送信、予約・購入などの外部操作を行わない。
- アプリのインターネット設定がOFFの場合、AI操作を無効化。
- AIをOFFにするとRAM上のAI会話履歴を破棄。

### Server

- `POST /ai/agent` を追加。
- OpenAI Responses API + Structured Outputsを利用し、SOUYAKU専用JSONスキーマで応答を固定。
- 既定モデル: `gpt-5.6-luna`。`SOUYAKU_AI_MODEL` で変更可能。
- OpenAIリクエストは `store: false`。
- `OPENAI_API_KEY` はサーバー環境変数のみ。Android APKへAPIキーを含めない。
- 1クライアントあたり既定30回 / 10分の簡易レート制限。
- `/health` に `aiConfigured` と `aiModel` を追加。
- `OPENAI_API_KEY` が未設定の場合、通常のWebSocket中継は動作し、AIエンドポイントだけ503を返す。

## Version

- versionName: `1.3.33`
- versionCode: `38`
