# Find Bridge — 開発者向けREADME

利用・インストール方法は[利用者向けREADME](../README.md)に記載しています。

## 構成

アプリ・テスト・ビルド定義はKotlin / Kotlin DSLです。画面はAndroidのネイティブViewとMapLibre Nativeで構成しています。認証時の `AuthActivity` だけがWebViewを使用し、接続後に破棄します。

`apple-session.js` は公式ページの読み取り用応答をKotlinへ渡す小さなアダプターです。AndroidXのorigin制限付きメッセージリスナーを使用し、パスワードや確認コードにはアクセスしません。

2026-10-30にレジストリで確認した安定版を固定しています。

| 項目 | バージョン |
| --- | --- |
| compileSdk / targetSdk | Android 17 / API 37 |
| minSdk / 配布ABI | API 28 / arm64-v8a |
| Kotlin | 2.4.21 |
| Android Gradle Plugin | 9.5.0 |
| Gradle | 9.8.2 |
| Android Build Tools | 37.0.0 |
| MapLibre Native Android | 13.7.1 |
| AndroidX WebKit | 1.18.0 |
| AndroidX Test Runner / Core | 1.7.0 |
| AndroidX Test JUnit | 1.3.0 |

## ビルド

JDK 21とAndroid SDKを用意してください。

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0'
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

デバッグAPKは `app/build/outputs/apk/debug/app-debug.apk` です。

### リリース署名

署名鍵とパスワードはGit管理の外に置きます。継続した更新には同じ鍵が必要です。署名プロパティの書式:

```properties
storeFile=/absolute/path/to/private-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

```bash
export FINDBRIDGE_SIGNING_PROPERTIES='/absolute/path/to/signing.properties'
./gradlew :app:assembleRelease :app:lintRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

署名プロパティ未設定ではunsigned APKになります。debug署名をreleaseへ流用しません。R8最適化・リソース縮小・arm64限定・JNIライブラリの圧縮を有効にしています。JNIはインストール時に展開されるため、APKのサイズと端末上の使用量は異なります。Google Play向けApp Bundleの配布は行っていません。

## データ取得

- `AppleRepository`: origin/URLを制限した認証応答から、セッションとデバイス情報を受け取ります。以後はKotlinのHTTPクライアントで `refreshClient` を呼びます。30秒更新はフォアグラウンド中のみです。
- `UrlPolicy`: AppleのHTTPSホストと `initClient` / `refreshClient` だけを許可。認証WebViewでも他のfmip操作を拒否します。サウンド・ロック・消去のAPIはありません。
- `SecureSessionStore`: Android KeystoreのAES-GCM。セッションと追加連携設定は別の鍵・領域です。世代番号と直列化した保存で、サインアウト／切断後の応答復活を防ぎます。
- `FamilyMember` / `AppleDevice`: `userInfo.membersInfo` の所有者IDと各デバイスの `prsId` を照合します。`fmlyShare` 単独では所有者を判定しません。欠損位置、未知の電池、位置情報オフを区別します。
- `LocationSyncRepository`: 所有者が選択したJSON、または証明書を固定したHTTPSのsnapshotを読み取る追加データ経路です。[形式と制約](LOCATION-SOURCES.md)を参照してください。

人・持ち物・Pencil ProのAppleサービスからの自動取得は未実装です。現在のデバイス用Webセッションからは必要な情報・鍵が得られません。家族のデバイスの位置から人の位置を推測する処理はありません。

## 描画

`MapStyles` が標準／航空写真／ハイブリッドと明暗を生成します。`GlassScene` は実際のMapLibre画像をメモリ内で縮小し、ワーカースレッドでぼかします。Android 13以降ではAGSLで屈折・彩度・縁のハイライトを追加し、ネイティブのGPUぼかしも使用します。それ以前はぼかし画像と色の重ね合わせへフォールバックします。UI文字列は背景画像に混ぜません。Apple固有のレンダラー・画像・SF Symbolsは使用していません。

## テスト

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

複数のエミュレータがあるときは対象を明示してください。

```bash
export ANDROID_SERIAL=emulator-5556
./gradlew :app:connectedDebugAndroidTest
```

実アカウントのテストは、所有者が接続済みのテスト端末に限って明示的に実行します。

```bash
adb -s emulator-5556 shell am instrument -w \
  -e live true \
  -e class dev.findbridge.android.NativeSessionLiveTest,dev.findbridge.android.FamilyMetadataLiveTest \
  dev.findbridge.android.test/androidx.test.runner.AndroidJUnitRunner
```

`NativeFeaturesTest` の位置データは明示的なテストfixtureです。実アカウントの人・持ち物の取得確認には数えません。位置・所有者名・認証情報をログやコミットへ含めないでください。

GitHub Actionsはdebug/releaseのビルド、Lint、単体テストを実行します。CIには署名鍵とAppleの認証情報を置きません。署名済みAPKは非公開GitHub Releasesで配布します。エミュレータ・認証・地図の実表示はローカルの[検証記録](../VERIFICATION.md)を参照してください。
