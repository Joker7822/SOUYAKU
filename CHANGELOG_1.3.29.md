# SOUYAKU 1.3.29

## Realtime Pipeline

1.3.28 の並列処理を、低遅延と実機差制御を重視したリアルタイムパイプラインへ更新しました。

### 音声認識

- Android 13 / API 33+ では `SpeechRecognizer.checkRecognitionSupport()` で固定言語の音声認識モデル状態をセッション開始前に確認。
- 端末内モデルが対応済みだが未導入の場合、インターネット利用が許可されていれば `triggerModelDownload()` を要求。
- API 33+ の認識Intentで `FORMATTING_OPTIMIZE_LATENCY` を指定。
- 通常マイク認識の無音エンドポイントを短縮（possibly complete 280ms / complete 480ms）。認識サービスがこのHintを無視する場合があります。

### Dual Audio VAD

- Dual Audioの2本のPCMストリームをSOUYAKU側でRMS監視。
- 発話を検出後、約430msの無音を検出すると、その認識セッションのPCM pipeを閉じてEOFを通知。
- 連続PCMで固定7秒タイムアウトまで待ちやすかった問題を軽減。
- SELF / PARTNERそれぞれのキャプチャbytes・voiced frameを記録するhealth APIを追加。

### 翻訳・TTS

- 対面通訳開始時、固定言語では双方向ML Kit翻訳モデル準備とASRモデル事前確認を並列実行。
- TTS待ち時間に応じて読み上げ速度を 1.00 / 1.06 / 1.12 / 1.18 倍へ動的調整。
- 直近TTSと高類似度の認識結果を残留エコーとして抑制。
- Dual Audio / 通常対面 / トークン通話の主要パイプラインにレイテンシログを追加。

### レイテンシ診断

Logcat `SOUYAKU-Latency` で以下を出力します。

- ASR完了まで
- 翻訳所要時間
- TTS待ち時間
- TTS開始までの総時間
- TTS終了までの総時間

### Dual Audio端末プロファイル

- Build fingerprint と入出力デバイス構成をキーに、Dual Audio分離テストの前回結果を保存。
- 次回検出時に前回結果を表示。ただしAudio HAL状態は変化し得るため、開始時の実ルート検証は毎回行います。

## Version

- versionName: `1.3.29`
- versionCode: `34`
