# 検証記録

2026-10-30（JST）。アプリ3.0.0。認証情報、正確な位置・住所、所有者名は記録しません。

## 環境と成果物

- macOS / Apple Silicon、Android Emulator 37.2.13
- Android 17 / API 37 / arm64、Pixel 8 AVD
- デバッグ検証：`FindBridge_API37` / `emulator-5556`
- リリース検証：新規 `FindBridge_Release_API37` / `emulator-5558`
- compileSdk / targetSdk 37、minSdk 28
- JDK 21、Kotlin 2.4.21、AGP 9.5.0、Gradle 9.8.2
- `FindBridge-3.0.0-arm64-release.apk`：5,411,654 bytes（5.16 MiB）
- APK SHA-256：`35d32551d375463abddf725d908bbb781eceaf00d227831a5723e076ed8141d1`
- 署名証明書 SHA-256：`f2150829315c625a37d84eb48385635aa1ede42696b28f8c4204e239231be670`

専用RSA 3072ビット鍵によるAPK v2署名を `apksigner verify` で確認。配布APKはdebuggableではなく、ABIはarm64-v8aのみです。署名鍵・パスワードはリポジトリ外に保管しています。

2.0.0の複数ABIを含むdebug APK（59,087,173 bytes）に対して90.84%縮小しました。R8、リソース縮小、arm64限定、圧縮JNIを使用しています。インストール後の展開サイズとは異なります。

## 自動検証

以下のローカルGradle実行が成功しています。

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease \
  :app:lintDebug :app:lintRelease :app:testDebugUnitTest
```

- 単体テスト5件：許可URLの境界、位置なし・状態表示など。失敗0件。
- debug / release Lint：ともに `No issues found.`
- API 37エミュレータのinstrumentation 7件：すべて成功。
  - `NativeUiTest` 2件：ネイティブMapView、主画面のWebView不在、欠損位置。
  - `NativeFeaturesTest` 3件：所有者ID照合、位置情報オフ、追加ファイルの人・持ち物・Pencil振り分けとネイティブ詳細、暗色／航空写真スタイル。
  - `NativeSessionLiveTest` 1件：保存セッションからKotlin HTTPで実デバイスを取得、マーカーの可視範囲、無効なサウンド操作。
  - `FamilyMetadataLiveTest` 1件：実アカウントの家族メタデータとデバイス所有者の照合。

追加ファイルのテストは明示的なfixtureを使用し、終了時に設定とファイルを削除しています。実際の人・AirTag・Pencilの取得テストには含めません。

## 実アカウントとリリースAPK

利用者が明示的に接続を許可したテストアカウントを使用しています。

- Apple公式の認証ページで接続し、自動でネイティブ画面へ戻ること。
- WebViewを閉じた後も、KotlinのHTTP通信で実デバイスと位置を再取得できること。
- 家族のメンバーIDと各デバイスの所有者を照合できること。
- ネイティブの詳細表示、マーカー、一覧カードに隠れないカメラ位置を確認。
- 新しいエミュレータへ専用鍵で署名したrelease APKをインストールし、Appleへの初回接続と実デバイスの表示を確認。
- releaseアプリをforce-stopして起動し直しても、再ログインなしで実デバイスが表示されること。
- 同じ署名鍵で再ビルドした最終APKを上書きしても、保存セッションが維持されること。
- release主画面のUI階層にWebViewがないこと。

このFind Devices接続では追加の2段階認証は要求されませんでした。認証情報はアプリの設定やリポジトリへ保存していません。

## 画面の実表示

- ライト標準地図、ダーク標準地図、ライト航空写真、ダーク航空写真の描画をAPI 37エミュレータで目視確認。
- 航空写真は国土地理院の実タイルを読み込み、出典を表示。
- 地図の実画像に基づくガラス背景、浮いたタブ、ドラッグ可能な一覧、明暗に追従する文字と操作部を確認。
- 地図設定と外観設定の保存、Activityの再生成後の反映を確認。

## 実行していない操作と残る範囲

音を鳴らすリクエストは送信していません。サウンド、紛失モード、ロック、消去、デバイス削除のAPIはありません。

人・持ち物・Pencil ProのAppleサービスからの自動取得、実際のMac連携、Pencilの近距離探索は未実装／未検証です。HTTPS連携クライアントの契約は実装していますが、サーバーと復号処理は同梱していません。Macの暗号化データを読むための鍵抽出やOS保護設定の変更は行っていません。

物理Android端末、API 28の描画フォールバック、海外の高倍率航空写真、長時間運転、バックグラウンド更新は未検証です。Apple側の非公開サービスとセッションの有効期間に依存します。
