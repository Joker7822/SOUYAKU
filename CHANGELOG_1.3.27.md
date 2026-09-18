# SOUYAKU 1.3.27

## Dual Audio Engine

対面通訳向けに `SouyakuDualAudioEngine` を追加しました。

対応端末では以下を同時に実行します。

- イヤフォンマイク: 自分の言語を認識 -> 相手言語へ翻訳 -> デバイススピーカーへ出力
- デバイス内蔵マイク: 相手の言語を認識 -> 自分の言語へ翻訳 -> イヤフォンへ出力

### 実装

- 2本の `AudioRecord` を別々の入力デバイスへ `setPreferredDevice()` で要求。
- 録音開始後の `routedDevice` を確認し、実際に2入力が別デバイスへ分離された場合だけDual Audioを有効化。
- 各AudioRecordから独立したPCM pipeを作り、2個のSpeechRecognizerへ `RecognizerIntent.EXTRA_AUDIO_SOURCE` として供給。
- 自分側/相手側でSpeechRecognizerインスタンスを分離。
- 翻訳/TTSを子ジョブへ渡し、次の認識を待たせない構造へ変更。
- TTSは一度ファイルへ合成し、`MediaPlayer.setPreferredDevice()` でイヤフォン/本体スピーカーを方向別に要求。
- AcousticEchoCanceler対応端末では各AudioRecordセッションへAECを有効化。
- OEM Audio HALがデュアル入力を拒否する場合や、同時SpeechRecognizerが継続できない場合は1.3.26の「話す」方式へ自動フォールバック。

### 制約

Androidの公開APIではOEM Audio Policy / Audio HALの制約を回避できません。したがって、Dual Audioは機種ごとに実ルートを検証してから有効化します。

- versionName: `1.3.27`
- versionCode: `32`
