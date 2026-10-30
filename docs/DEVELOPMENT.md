# Find Bridge — 開発者向けREADME

利用・インストール方法は[利用者向けREADME](../README.md)に記載しています。

## 構成

アプリ・テスト・ビルド定義はKotlin / Kotlin DSLです。画面はAndroidのネイティブViewとMapLibre Nativeで構成しています。Apple認証の `AuthActivity` とGoogle公式認証の `GoogleAuthActivity` がWebViewを使用します。Googleのデータ取得にも別プロセスの短時間のWebViewを使用しますが、通常の地図・一覧・詳細はすべてネイティブです。

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
- `UrlPolicy`: 読み取り通信はAppleのHTTPSホストと `initClient` / `refreshClient` だけを許可。認証WebViewでも他のfmip操作を拒否します。サウンドは独立した明示操作の経路だけに限定します。ロック・消去のAPIはありません。
- `SecureSessionStore`: Android KeystoreのAES-256-GCM。セッション、ログイン情報、追加連携設定は別の鍵・領域です。世代番号と直列化した保存で、サインアウト／切断後の応答復活を防ぎます。
- `FamilyMember` / `AppleDevice`: `userInfo.membersInfo` の所有者IDと各デバイスの `prsId` を照合します。`fmlyShare` 単独では所有者を判定しません。欠損位置、未知の電池、位置情報オフを区別します。
- `LocationSyncRepository`: 所有者が選択したJSON、または証明書を固定したHTTPSのsnapshotを読み取る追加データ経路です。[形式と制約](LOCATION-SOURCES.md)を参照してください。

人・持ち物・Pencil ProのAppleサービスからの自動取得は未実装です。現在のデバイス用Webセッションからは必要な情報・鍵が得られません。家族のデバイスの位置から人の位置を推測する処理はありません。

## 認証情報と再接続

`LoginActivity` はネイティブの入力画面です。`LoginVault` はメールアドレスとパスワードを暗号化し、保存・自動接続・一時停止・再試行時刻だけを平文の方針設定に置きます。暗号化はワーカー上で行います。鍵はエクスポート不可で、ハードウェア保護の有無はAndroid端末に依存します。

接続中の一時受け渡しはプロセス内の一度だけ取得できるオブジェクトです。Intent、ログ、savedInstanceStateへパスワードを入れません。入力欄と認証WebViewは状態保存を無効にし、認証WebViewの履歴・フォームをBundleへ保存しません。FLAG_SECUREを設定します。保存パスワードは再表示しません。

セッション復元を優先し、期限切れまたは保存セッションなしの場合だけ自動接続します。単なるネットワーク障害では再認証しません。自動試行は5分間隔で、失敗やキャンセルで停止し、所有者の再接続操作で再開します。バックグラウンド認証・確認コードの自動取得はありません。

`apple-autologin.js` は保存を許可された情報をメモリ内で挿入し、HTTPSの `idmsa.apple.com` / `account.apple.com` の認証パスだけへ投入します。`apple-login-landing.js` はiCloudの接続入口、`apple-login-relay.js` はApple認証iframeの実際の親子Windowと完全一致するoriginを各段で検証します。ボタンは従来のIDに加えて、8言語のサインイン／続けるに対応します。ブリッジに資格情報を流しません。新しい確認コードは本人が入力します。

自動接続はネイティブの状態表示で公式ページを覆い、追加確認のときだけ表示を切り替えます。WebViewのautofillは自動接続中に無効とし、親の入力フォームのautofillセッションも接続時に閉じます。DOMの変化、描画フレーム、フォーカスとIMEのイベントで入力と送信を進めます。HTML到着を待つ固定時間や周期ポーリングはありません。120秒のタイマーは停止時のエラー通知用で、通常処理の開始を遅らせるものではありません。フォーカス中断は送信済みと扱わず、復帰イベントで再開します。入力画面へ戻った場合は暗号化領域から保存情報を読み直します。

## Google Find Hub（試験実装）

`GoogleAuthActivity` / `GoogleLocationService` / `GoogleSession` は非公開の `:google` プロセスで動きます。最初のWebView作成前に `WebView.setDataDirectorySuffix("googleFindHub")` を指定し、AppleのCookie削除と分離します。アカウント識別子と接続フラグだけを別のKeystore名前空間で暗号化し、GoogleのCookieはこのWebViewのアプリ専用領域に保持します。CookieデータベースをKeystore暗号化したとは扱いません。

`google-devices.js` はGoogleの公式ページがDOMに公開する端末名と `data-slot-id` / `data-location-lat` / `data-location-lng` を読みます。地図カメラの中心から端末位置を推測せず、マーカーのslotをカタログのindexに照合します。端末を選ぶ／一覧へ戻る操作だけを使い、サウンド・紛失・消去の操作は呼びません。通常のFind Hub操作画面はネイティブの読み込みパネルで覆います。本人確認や同意のダイアログが現れた場合は自動操作を止め、Google公式の確認を所有者に表示します。確認ダイアログ以外へのクリック・ポインター・キー入力はキャプチャ段階で遮断し、同意を自動承認しません。Googleの秘密鍵、Cookie、入力パスワードをKotlin側に取り出す処理はありません。UA偽装やGoogleの安全性チェックの回避もしません。

`GoogleProtocol` は正規化した端末情報を検証し、`GoogleRepository` へMessengerで渡します。最大500件・240,000文字、座標は有限かつ緯度経度の対が有効な場合だけ受け付けます。相対的な最終検知ラベルを現在時刻に書き換えません。GoogleとAppleの同名端末は別IDです。

Googleはフォアグラウンドで2分以上経過したときと明示的な更新時に読み込みます。DOMアダプターはMutationObserverと描画フレームで進み、端末ごとの固定待機はありません。9秒／90秒の失敗期限で無応答時だけ打ち切ります。認証画面は実カタログ取得時点で閉じ、位置の取得はネイティブ画面から続けます。取得完了時はWebViewを破棄し、画面を離れるとbound serviceも解放します。非公開APIのOAuthトークンやAndroidのマスタートークンは使用しません。

## サウンドとUWB

`SoundConfirmation` の肯定ボタンからだけ `AppleRepository.playSound` を呼びます。現在のAppleセッション内に存在する対応デバイスだけを受け付け、`playSound` だけを許可するURL検証を通します。自動更新・自動ログイン・バックグラウンド・再試行キューからは呼びません。送信を自動再試行せず、応答成功と実機の発音成功を区別します。Googleと追加連携のIDは送信不可です。サウンドのテストはローカルのカウンターだけを呼び、実機への送信はしていません。

UWBは未実装です。Android単体で標準の「探す」の対象と直接接続するための未解決点と一次資料を[UWB調査](UWB.md)に記載しています。iPhone側の連携アプリを前提とする実装は含めません。

現時点で実アカウントのブラウザ上の端末一覧・位置と、アプリ内の公式サインイン開始を確認しています。HTML fixtureでDOM変換・slot照合・音操作なしを検証しています。アプリ内の実アカウント接続から取得まで、背景での再取得、オフライン位置のPIN認証は未確認です。fixtureの成功を実アカウントの成功に数えないでください。

## 描画

`MapStyles` が標準／航空写真／ハイブリッドと明暗を生成します。`GlassScene` はMapLibreのSurfaceViewをPixelCopyで最大幅640pxへ直接縮小して取り込み、ワーカースレッドでぼかします。TextureViewでは縮小済みgetBitmap、取得できない場合は従来のmap.snapshotへフォールバックします。全解像度のCPU画像を毎回割り当てる経路を通常は通りません。Android 13以降ではAGSLで屈折・彩度・縁のハイライトを追加して描画します。それ以前はぼかし画像と色の重ね合わせへフォールバックします。UI文字列は背景画像に混ぜません。Apple固有のレンダラー・画像・SF Symbolsは使用していません。

移動中の更新はChoreographerのVsync通知で進み、120Hzなら通知間隔は約8.3msです。`MapFrameRate` が同じ解像度で使える高リフレッシュレートを要求し、MapLibreの60fps上限もその値に合わせます。OSの省電力・温度制御による制限は尊重します。固定140msの移動中制限はありません。取り込みとぼかしは1枚だけを処理し、完了前の要求をキューに溜めません。

`GlassScene` は保存画像の地理的な4点と現在の地図投影から変換を作り、新しい画像を待つ間も背景の位置・倍率・回転・傾きを毎フレーム合わせます。AGSLと従来Canvasの両方へ適用します。移動中は最大320px幅と対応するぼかし半径で処理量を減らし、停止後に640pxへ仕上げます。静止中はフレームコールバックと高リフレッシュ要求を解除します。Vsyncの目標と、実際に完成できた新規画像数・GPU使用率は区別してください。

## 画面・言語

`NativeMotion` はAndroidX SpringAnimationを再ターゲットしてタッチの速度を継承します。シートは `SheetGestureLayout` と `SheetDetents`、タブは `NativeTabBar`、ページは `PageMotion`、設定パネルは `GlassPanel` が担当します。モーダルは子のカードだけでなくホスト全体を既存オーバーレイより高いZへ置き、背面の一覧・タブへのタッチを遮ります。退場中のページは描画専用overlayへ移し、二重タップやアクセシビリティの重複を避けます。アプリの「動きを減らす」とOSのアニメーション無効設定を尊重します。

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

### 実際のR8リリースAPKの認証検証

`verification` はAndroid 11以降で使う独立したオプトインのテストドライバーです。製品APKのKotlin/AndroidXがR8で削除・改名されても、テスト自身の依存関係で動作します。配布APKには含めません。`FINDBRIDGE_SIGNING_PROPERTIES` を設定し、対象のリリースAPKと同じ鍵で署名してください。

```bash
./gradlew :app:assembleRelease :verification:assembleDebug :verification:lintDebug
adb -s DEVICE install -r app/build/outputs/apk/release/app-release.apk
adb -s DEVICE install -r verification/build/outputs/apk/debug/verification-debug.apk
adb -s DEVICE shell am instrument -w \
  -e releaseauth true -e keyboard true -e interrupt true -e retry true \
  -e class dev.findbridge.verification.ReleaseLoginLiveTest \
  dev.findbridge.verification/androidx.test.runner.AndroidJUnitRunner
adb -s DEVICE uninstall dev.findbridge.verification
```

所有者が保存済みの暗号化ログイン情報を持つ端末だけで実行します。パスワードをテスト引数へ渡しません。実際のネイティブタッチ、ソフトキーボード、キャンセル後の再接続、10秒間のフォーカス中断を検証します。`-e completedField FIELD` には、同じAPKのR8 mappingにある `LoginActivity.completed` の改名先を指定でき、終了だけでなく成功状態も確認できます。実際の接続操作でローカルの旧セッションを破棄して再認証します。端末をアンインストールして保存情報を失わせないでください。確認コードが必要なら所有者の操作を待ちます。

GitHub Actionsはdebug/releaseのビルド、Lint、単体テストを実行します。CIには署名鍵とAppleの認証情報を置きません。署名済みAPKは非公開GitHub Releasesで配布します。エミュレータ・認証・地図の実表示はローカルの[検証記録](../VERIFICATION.md)を参照してください。

`MapIdleRenderingTest` は標準／航空写真の静止時のスナップショット数と地図描画数、取り込み解像度と実画素を確認します。`GoogleDataTest` は合成の公開座標を使い、異なる端末のマーカー混同や遠隔操作を検出します。`SavedLoginFormLiveTest` の `-e keyboard true` はソフトキーボードを表示した接続経路です。
