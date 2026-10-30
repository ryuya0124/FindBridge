# プライバシー / Privacy

更新日: 2026-10-31。Find Bridgeの公開APKに適用します。

## 利用するデータ

Find Bridgeは利用者が接続したApple / Googleのサービスから、アカウント識別情報、端末名・機種・画像・電池・位置・状態、返却される場合はIMEI・MEID・登録日・ネットワーク名などを取得して端末内に表示します。家族の情報は接続先サービスが許可して返す範囲です。人・持ち物の追加データは利用者が選んだファイルや設定先から読み込みます。

開発者が運営する認証・位置の中継サーバー、広告SDK、解析SDKはありません。位置・端末情報を開発者へ送信する機能はありません。公開リポジトリにも利用者のログイン情報は含めません。

## 保存と通信

- Appleのログイン情報は保存を選んだ場合だけ、Android Keystoreの端末専用AES-GCM鍵で暗号化してアプリ専用領域へ保存します。Apple認証セッションとGoogle本人識別情報は別の鍵で保存します。バックアップ・端末間転送は無効です。
- Googleのアカウント選択と権限確認はGMSで行い、要求するOAuthスコープは `openid` と `userinfo.email` です。Googleのパスワード・2段階認証コードをアプリ独自の保存領域へ保存しません。短時間使うUserInfoのアクセストークンも保存しません。
- Googleの公式画面での認証とFind Hubの読み取りには、Appleとは分離したアプリ専用WebViewプロファイルを使用します。このプロファイル内のCookie・サイトデータはGoogle / Android WebViewが保持します。Cookieデータベース自体をKeystoreで暗号化しているわけではありません。
- 端末の位置・詳細と地図のガラス描画用画像はメモリで扱います。公式CDNへの端末画像要求にはアカウントのCookie・トークンを添付しません。地図のタイルは地図提供元がディスクにキャッシュする場合があります。
- Apple / Googleへの認証とデータ要求、地図提供元へのタイル要求はそれぞれの提供元へ送ります。IPアドレスや地図の表示範囲は通信先に伝わります。追加連携は利用者が設定したサーバーへ通信します。
- 紛失モードを明示送信した場合は、対象端末・入力した連絡先・メッセージ・必要な新規ロックコードをAppleへ送ります。これらの入力値はアプリに保存しません。

## 削除と問い合わせ

Appleは「サインアウト」、Googleは「Googleアカウントの接続を解除」から、アプリ内の接続情報を削除できます。Appleの保存ログイン情報だけを削除する操作もあります。アプリのデータ消去やアンインストールでもアプリ専用領域が削除されます。接続先のクラウドにある端末の登録やGoogleアカウントの権限自体を削除する操作ではありません。Googleの権限はGoogleアカウントの設定でも管理できます。

利用者が選んだ追加ファイルは利用者自身が削除してください。問い合わせは[GitHub Issues](https://github.com/ryuya0124/FindBridge/issues)へお願いします。公開のIssueにはパスワード・確認コード・IMEI・正確な位置・本人や家族の情報を掲載しないでください。

## English

Find Bridge displays data from the Apple and Google accounts you connect, including device information and available locations. It has no developer-operated authentication or location relay, advertising SDK, or analytics SDK. Account and location data are not sent to the developer.

Optional saved Apple credentials and account/session records are encrypted locally with Android Keystore keys. Google account authorization uses GMS and only the `openid` and `userinfo.email` scopes. Google passwords, verification codes, and UserInfo access tokens are not saved by the app. Google's separate, app-private WebView profile retains its own cookies and site data; that cookie database is not encrypted with the app's Keystore keys.

Requests go directly to the selected providers. Map providers receive the requested map area and your IP address. Lost Mode contact details and any required new lock code are sent to Apple only after your explicit final confirmation and are not persisted by the app. Disconnect accounts in the app, or clear app data, to remove local connection records. This does not remove devices from the providers or revoke Google permissions. Contact the project through GitHub Issues without posting private account or device data.
