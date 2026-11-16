# 依存関係・API・セキュリティ点検

確認日: 2026-11-16。対象は3.3.1です。

## ライブラリとビルド環境

Google MavenとMaven Centralの各 `maven-metadata.xml` を取得し、安定版を照合しました。実行ライブラリはすでに最新の安定版で、今回の更新対象はAGP・Gradle・CIのJavaセットアップです。

| 項目 | 使用バージョン |
| --- | --- |
| AGP | 9.5.1（9.5.0から更新） |
| Gradle | 9.9.1（9.8.2から更新） |
| Kotlin / stdlib | 2.4.21 |
| Material Components | 1.14.0 |
| Google Play services auth | 22.0.0 |
| MapLibre Android SDK | 13.7.1 |
| AndroidX WebKit | 1.18.0 |
| AndroidX WindowManager | 1.5.1 |
| AndroidX DynamicAnimation | 1.1.0 |
| AndroidX Test runner / core / ext JUnit | 1.7.0 / 1.7.0 / 1.3.0 |
| JUnit | 4.13.2 |

AGP 9.5系はAPI 37をサポートし、必要なGradleは9.6.0以降です。[AGP公式リリースノート](https://developer.android.com/build/releases/agp-9-5-0-release-notes)で互換性を確認しました。CIの `actions/setup-java` は6.3.0へ更新し、すべてのActionsをコミットSHAで固定しています。

## セキュリティ修正と検証

[Gradle 9.9.1](https://docs.gradle.org/9.9.1/release-notes.html)には、バージョンカタログのコード生成と、デーモンへの接続に関する3件のセキュリティ修正が含まれます。これはビルド環境の修正であり、Find BridgeのAndroidアプリが侵害されたという意味ではありません。

- [GHSA-3cfg-jpcr-gfp3](https://github.com/gradle/gradle/security/advisories/GHSA-3cfg-jpcr-gfp3): バージョンカタログから生成されるアクセサー。
- [GHSA-gqrr-4j89-5267](https://github.com/gradle/gradle/security/advisories/GHSA-gqrr-4j89-5267): デーモンの登録情報の扱い。
- [GHSA-pcp8-98xm-c6pr](https://github.com/gradle/gradle/security/advisories/GHSA-pcp8-98xm-c6pr): 古いデーモンの登録情報の扱い。

GradleのZIP・wrapper JARを公式SHA-256と照合し、ZIPのハッシュをwrapper設定へ固定しました。依存ファイルはGradleの検証メタデータへSHA-256を記録し、Linux・Windows用aapt2も追加しました。検証値を意図的に変えたオフラインビルドが失敗することを確認し、その後元の値を復元しています。メタデータの初回生成だけで配布元の正当性が証明されるわけではないため、依存更新時は公式配布元と差分のレビューが必要です。[Gradleの依存検証](https://docs.gradle.org/current/userguide/dependency_verification.html)

解決されたMavenの実行依存83件を[OSVの公開API](https://google.github.io/osv.dev/api/)で照合し、該当するアドバイザリーは0件でした。この結果は照合日時点の登録情報とMaven座標に対するもので、組み込みC++コード・システムWebView・GMS本体の脆弱性がないことを保証するものではありません。

ネイティブの応答読み取りは共通化し、宣言サイズの超過を先に拒否、長さ不明の応答は上限を1バイト超えた時点で停止します。アカウント変更・更新確認のキャンセル後の読み取りも中断します。AppleのサービスURLでは、パーセントエンコードされたパスの別表現とフラグメントを拒否し、操作ごとに許可するパスを分けています。入力制限とキャンセルはローカルの固定データで試験しています。

## 外部APIの確認範囲

| 接続先 | 確認できたこと | 残る確認 |
| --- | --- | --- |
| Apple Find Devices | `https://www.icloud.com/find` と公開クライアントの取得がHTTP 200。公開シェルは2642Build23 | 認証済みの端末一覧・家族情報・非公開JSONスキーマは再取得していません |
| Google本人情報 | OpenID discoveryの `userinfo_endpoint` が実装の `https://openidconnect.googleapis.com/v1/userinfo` と一致。`openid` / `email` と解析対象のclaimsも一致 | 実アカウントでのGMS同意とトークン取得は今回未実行 |
| Google Find Hub | 公開入口から `/android/find/about` への匿名リダイレクトを確認。入口・案内ページは既存のURLポリシーで許可 | ログイン後のDOM・位置取得・追加確認は未確認 |
| GitHub Releases | `2026-03-10` ヘッダーでHTTP 200、選択されたAPI版も一致。配布APKのdigestフィールドあり | 将来のサービス変更には再点検が必要 |

Googleの現在の公式手順では、データアクセスの認可には `AuthorizationClient` を使用します。本アプリもこのAPIを使っており、削除された旧Google Sign-In APIへの切り替えは行いません。[Googleの認可手順](https://developer.android.com/identity/authorization)、[GMSリリースノート](https://developers.google.com/android/guides/releases)

Google本人情報の公開契約は[OpenID discovery](https://accounts.google.com/.well-known/openid-configuration)で照合しました。Appleの公開Find My開発者資料はアクセサリーのネットワーク対応に関するもので、個人アカウントのWeb版端末取得APIの互換性を保証する仕様ではありません。[AppleのFind My資料](https://developer.apple.com/find-my/)

認証済みサービスに関して「API変更なし」「実ログイン成功」とは判定していません。HTML・JSONのローカルfixtureと通常の回帰試験は、実サービスの互換性確認と分けて記録します。実機は使わず、実際のサウンド・紛失モード・消去リクエストは送信していません。
