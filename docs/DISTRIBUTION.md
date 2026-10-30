# 配布とGoogleログイン

利用方法は[利用者向けREADME](../README.md)、データの扱いは[プライバシー](../PRIVACY.md)、ビルドは[開発者向けREADME](DEVELOPMENT.md)を参照してください。

## 公開APK

[GitHub Releases](https://github.com/ryuya0124/FindBridge/releases)から署名付きarm64 APKを配布します。GitHubアカウントや開発者による利用者登録は必要ありません。Android 9以降に対応し、Google接続にはGoogle Play開発者サービスと現在のWebView機能が必要です。GMSのない端末ではGoogle接続を利用できません。Google Playでの配信はまだ行っていません。

3.0.0以降の公開版は同じ専用鍵を使います。正規の公開APKは次の証明書で署名しています。APKのSHA-256はリリースごとの `SHA256SUMS.txt` で確認できます。

```text
package: dev.findbridge.android
signing certificate SHA-256:
f2150829315c625a37d84eb48385635aa1ede42696b28f8c4204e239231be670
```

## 自分のGoogleアカウントで接続する

1. 「自分」→「Googleアカウントに接続」→「アカウントを選択」を押します。
2. GMSの画面で、Find Hubに接続する自分のGoogleアカウントを選びます。端末0台の一覧にも対応します。
3. 必要な場合はGoogleの公式画面でログイン・2段階認証・PIN・同意を操作します。
4. アカウントの一致と実際の端末一覧を確認すると、ネイティブ画面へ戻ります。位置や端末情報は続けて取得します。

公開版に特定のメールアドレス、ドメイン、開発者のアカウントを強制する処理はありません。Googleアカウントは現在1つを接続できます。変更する場合はアプリの「Googleアカウントの接続を解除」から接続し直してください。Apple側のアカウントは別に維持します。

Googleが返すデータには端末ごとの差があります。初回設定が済んでいない、一覧が空、本人確認やオフライン位置の復号が未完了の場合、取得できないことがあります。組織・子ども・Advanced Protectionのアカウントは管理者やGoogle側のポリシーにも依存します。

## Google OAuthの公開条件

現在の要求は `openid` と `https://www.googleapis.com/auth/userinfo.email` の基本本人情報だけです。Find HubのセッションをOAuthスコープだけで取得するわけではありません。無効だった `android_device_manager` は要求しません。

Googleの公式資料では、基本本人情報のみの要求にはTestingの利用者リストと7日失効の例外があります。このため、テスト利用者の追加を一般ユーザーへ要求する設計にはしていません。Google Cloudのブランド審査・Google Playの公開審査の完了を示すものではありません。Cloud Consoleの本番公開状態と、実ユーザー2人目の初回ログインは今回の配布確認では検証していません。

- [Google OAuthの公開状態](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview)
- [Testingと基本本人情報の例外](https://support.google.com/cloud/answer/15549945)
- [Android OAuthクライアントの設定](https://developer.android.com/identity/authorization)

## 独自ビルド・エラーの調査

公開APKと異なる鍵・パッケージでビルドする場合は、その組み合わせのAndroid OAuthクライアントを自分のGoogle Cloudプロジェクトへ登録してください。公開APKに秘密のクライアントキーを同梱する必要はありません。一般配布するプロジェクトではExternalを選び、ブランド情報・プライバシーURLなどを設定してください。

| 症状 | 確認する内容 |
| --- | --- |
| `invalid_scope` | 公開版を利用し、古い `android_device_manager` を要求する独自ビルドを使わない |
| `DEVELOPER_ERROR` / 設定エラー | Android OAuthクライアントのpackageと署名SHA-1を照合する |
| `org_internal` / 管理者によるブロック | Google CloudのAudience、所属組織やアカウントの制限を確認する |
| アプリ内のGoogle画面で追加確認 | 本人が画面を操作する。Chrome側のログインとは別のアプリ専用プロファイル |
| Googleのアカウントが一致しない | アプリのGoogle接続を解除し、GMSと公式画面で同じアカウントを選ぶ |
| 位置がない端末 | Find Hubがそのアカウントへ返す位置を確認する。位置を推測して表示する処理はない |

配布前には単体・instrumentation・Lint、R8後の実APK、署名・ハッシュ・デバッグ無効を確認します。実アカウントの検証は明示オプトインだけで実行し、遠隔ロック・サウンドを実行しません。詳しい結果と未確認範囲は[検証記録](../VERIFICATION.md)に記載します。
