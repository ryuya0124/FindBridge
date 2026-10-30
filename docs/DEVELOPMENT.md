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
| AndroidX DynamicAnimation | 1.1.0 |
| AndroidX WindowManager / window-java | 1.5.1 |
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
- `SecureSessionStore`: Android KeystoreのAES-256-GCM。セッション、ログイン情報、追加連携設定は別の鍵・領域です。世代番号と直列化した保存で、サインアウト／切断後の応答復活を防ぎます。
- `FamilyMember` / `AppleDevice`: `userInfo.membersInfo` の所有者IDと各デバイスの `prsId` を照合します。`fmlyShare` 単独では所有者を判定しません。欠損位置、未知の電池、位置情報オフを区別します。
- `LocationSyncRepository`: 所有者が選択したJSON、または証明書を固定したHTTPSのsnapshotを読み取る追加データ経路です。[形式と制約](LOCATION-SOURCES.md)を参照してください。

人・持ち物・Pencil ProのAppleサービスからの自動取得は未実装です。現在のデバイス用Webセッションからは必要な情報・鍵が得られません。家族のデバイスの位置から人の位置を推測する処理はありません。

## 認証情報と再接続

`LoginActivity` はネイティブの入力画面です。`LoginVault` はメールアドレスとパスワードを暗号化し、保存・自動接続・一時停止・再試行時刻だけを平文の方針設定に置きます。暗号化はワーカー上で行います。鍵はエクスポート不可で、ハードウェア保護の有無はAndroid端末に依存します。

接続中の一時受け渡しはプロセス内の一度だけ取得できるオブジェクトです。Intent、ログ、savedInstanceStateへパスワードを入れません。入力欄と認証WebViewは状態保存を無効にし、認証WebViewの履歴・フォームをBundleへ保存しません。FLAG_SECUREを設定します。保存パスワードは再表示しません。

セッション復元を優先し、期限切れまたは保存セッションなしの場合だけ自動接続します。単なるネットワーク障害では再認証しません。自動試行は5分間隔で、失敗やキャンセルで停止し、所有者の再接続操作で再開します。バックグラウンド認証・確認コードの自動取得はありません。

`apple-autologin.js` は保存を許可された情報をメモリ内で挿入し、HTTPSの `idmsa.apple.com` / `account.apple.com` の認証パスだけへ投入します。`apple-login-landing.js` はiCloudコンテナ内のApple認証iframeのボタン位置を検証します。ブリッジに資格情報を流さず、表示中WebView内のサインイン操作だけを行います。公式画面が変わった場合には手動入力へ戻れます。新しい確認コードは本人が入力します。

## 描画

`MapStyles` が標準／航空写真／ハイブリッドと明暗を生成します。`GlassScene` は実際のMapLibre画像をメモリ内で縮小し、ワーカースレッドでぼかします。Android 13以降ではAGSLで屈折・彩度・縁のハイライトを追加しを描画します。それ以前はぼかし画像と色の重ね合わせへフォールバックします。UI文字列は背景画像に混ぜません。Apple固有のレンダラー・画像・SF Symbolsは使用していません。

## 画面・言語

`NativeMotion` はAndroidX SpringAnimationを再ターゲットしてタッチの速度を継承します。シートは `SheetGestureLayout` と `SheetDetents`、タブは `NativeTabBar`、ページは `PageMotion`、設定パネルは `GlassPanel` が担当します。退場中のページは描画専用overlayへ移し、二重タップやアクセシビリティの重複を避けます。アプリの「動きを減らす」とOSのアニメーション無効設定を尊重します。

`AdaptiveLayout` は幅・高さ・Insets・ヒンジから配置とカメラ余白を計算します。`PostureObserver` はWindowManagerのFoldingFeatureを監視し、開閉時はActivityと選択を維持します。認証と設定パネルは物理ペイン内に収めます。iPadとiPhone Duoの配置原則を参考にした独自のネイティブ実装です。Appleの描画エンジンそのものではありません。

`AppLanguage` はAndroid 13以降ではLocaleManager、以前はConfiguration contextを使います。8言語の全キーと数量表現を同梱し、言語分割でAPKから削除されない設定です。UI文言を追加した場合は全リソースとテストを更新してください。地図の名前は存在する訳を優先します。

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

保存済み認証情報の自動接続は別の明示的なオプトインです。所有者が保存した認証情報を使い、ローカルセッションを消して公式のサインインから復元します。通常のCIでは実行しません。

```bash
adb -s emulator-5556 shell am instrument -w \
  -e autologin true -e class dev.findbridge.android.AutomaticLoginLiveTest \
  dev.findbridge.android.test/androidx.test.runner.AndroidJUnitRunner
```

`NativeFeaturesTest` の位置データは明示的なテストfixtureです。実アカウントの人・持ち物の取得確認には数えません。位置・所有者名・認証情報をログやコミットへ含めないでください。

GitHub Actionsはdebug/releaseのビルド、Lint、単体テストを実行します。CIには署名鍵とAppleの認証情報を置きません。署名済みAPKは非公開GitHub Releasesで配布します。エミュレータ・認証・地図の実表示はローカルの[検証記録](../VERIFICATION.md)を参照してください。
