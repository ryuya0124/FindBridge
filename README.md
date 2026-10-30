# Find Bridge

Appleの「探す」に近い操作感で、Androidから自分のAppleデバイスの位置を確認する非公式アプリです。地図、デバイス一覧、詳細カード、タブはネイティブUIです。Apple Accountへの接続時だけApple公式の認証ページを使用し、認証が済むとネイティブ画面に戻ります。

## 実装している機能

- ネイティブ地図の移動、拡大縮小、デバイスマーカー、全デバイスを表示するカメラ調整
- ドラッグして高さを変えられるデバイス一覧と、タップで開く詳細カード
- Appleから取得した位置、最終更新時刻、オンライン状態、取得可能な場合のバッテリー表示
- 更新ボタンと、アプリ表示中の30秒ごとの位置情報更新
- Android Keystoreで暗号化したセッションの保存、アプリ再起動後の更新、サインアウト
- 日本語の画面、元の更新時刻を保った古い位置情報の表示、通信エラー時の再試行

**サウンド再生は無効です。** 音を鳴らすAPI、紛失モード、ロック、消去、デバイス削除のAPIは実装していません。認証用WebViewでも、Find Devicesの読み取り用エンドポイント以外へのリクエストを拒否します。

AirTag一覧、人の位置共有、Android自体のFind Myネットワークへの登録には対応しません。下部の「人を探す」「持ち物」は対応範囲の説明を表示します。地図はApple Mapsではなく、OpenStreetMapのデータを使ったMapLibre Nativeです。

## 技術構成

2026-10-30にレジストリで確認した安定版を固定しています。

| 項目 | バージョン |
| --- | --- |
| アプリ、テスト、ビルド定義 | Kotlin / Kotlin DSL |
| compileSdk / targetSdk | Android 17 / API 37 |
| minSdk | Android 9 / API 28 |
| Kotlin | 2.4.21 |
| Android Gradle Plugin | 9.5.0 |
| Gradle | 9.8.2 |
| Android Build Tools | 37.0.0 |
| MapLibre Native Android | 13.7.1 |
| AndroidX WebKit | 1.18.0 |
| AndroidX Test Runner / Core | 1.7.0 |
| AndroidX Test JUnit | 1.3.0 |

アプリのUIとデータ処理はKotlinです。`apple-session.js` は認証ページ内の読み取り用応答をKotlin側へ渡す小さなアダプターです。パスワードや確認コードは読み取りません。

接続時にAppleの `initClient` / `refreshClient` 応答からデバイス情報とセッションを受け取り、その後の更新はKotlinのHTTPクライアントからAppleへ直接送信します。許可されたAppleのホストと読み取り用の2パスに限定しています。認証用WebViewは接続完了時に破棄します。

Appleの非公開Webサービスを利用するため、Apple側の変更やセッション期限切れでは再接続が必要になる場合があります。パスワードを保存して自動的に再ログインする処理はありません。

## ビルド

JDK 21とAndroid SDKを用意してください。

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0'
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

```bash
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 shell am start -n dev.findbridge.android/.MainActivity
```

GitHub Actionsも同じビルド・Lint・単体テストを実行します。開発用APKは非公開GitHub Releaseに保存しています。リポジトリは非公開です。ストア配信用署名は含みません。

## 検証

[検証記録](VERIFICATION.md) を参照してください。認証情報の不要なテストは次で実行できます。

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

既に認証済みのエミュレータで、保存セッションからのネイティブ通信とサウンド無効を検証する場合:

```bash
adb -s emulator-5556 shell am instrument -w \
  -e live true \
  -e class dev.findbridge.android.NativeSessionLiveTest \
  dev.findbridge.android.test/androidx.test.runner.AndroidJUnitRunner
```

## データの扱い

独自サーバー、広告、解析SDKはありません。パスワード・確認コードはソースや設定に保存しません。セッションはAndroid KeystoreのAES-GCMで暗号化し、アプリのバックアップと端末間転送は無効にしています。デバイス位置はアプリのメモリ内に保持し、検証記録・スクリーンショット・GitHubには含めません。

Appleとは提携していません。地図と依存ライブラリの出典は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) に記載しています。
