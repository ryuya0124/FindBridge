# Google接続とFind Hubの検証範囲

2026-10-30。Googleの本人認証と、Find Hubの端末・位置の取得は分けて扱います。

## 接続の流れ

1. `GoogleAuthActivity` のネイティブ画面からGMSのAccountPickerを開きます。Google Play services Auth 22.0.0のAuthorizationClientへ、公開スコープの `openid` / `https://www.googleapis.com/auth/userinfo.email` を要求します。
2. 受け取ったアクセストークンで、ネイティブHTTPSクライアントがGoogle UserInfoを取得します。確認済みメール・subject・選択アカウントの一致を検証し、本人識別情報だけを専用のAndroid Keystore領域へ暗号化します。トークンは保存せず、WebViewへ渡しません。
3. `GoogleWebSessionActivity` がAppleとは分離したGoogleのWebセッションを再利用します。未接続の場合は、GMSの旧 `weblogin:` 経路が発行するMergeSession URLでセッション引き継ぎを試みます。URLは同じGoogleプロセス内のメモリだけで扱い、Intent・ログ・ファイルには残しません。
4. Googleが本人操作を要求した場合だけ、アプリ内でGoogleの公式ページを表示します。パスワード、2段階認証、PIN、同意は利用者本人が操作します。アプリによる自動入力・同意の自動承認はありません。
5. 実際の端末一覧と、選択したアカウントに一致するページのアカウント情報が返ると、ネイティブの地図・一覧へ戻ります。位置情報はその後も読み取ります。存在しない位置・時刻・オンライン状態を作りません。

Googleのパスワードを独自フォームへ入力させたり、Appleの保存パスワードをGoogleへ流用したりしません。Google公式アプリのpackage/signatureを名乗る処理、他アプリのトークン・Cookie・復号鍵の取得もありません。

## 実機で確認した結果

Galaxy S26 Ultra / Android 17 / WebView 156.0.8078.47で検証しました。

- 専用Google CloudプロジェクトにAndroid OAuthクライアントを登録しました。パッケージは `dev.findbridge.android`、署名SHA-1は `8C:BE:28:75:35:54:59:B8:26:4F:8B:25:B6:E9:3D:B4:90:39:AA:79`。指定利用者のみのテストモードです。課金・クラウド実行サービスは作成していません。
- 公開スコープのGMS認証、Google UserInfoの確認、暗号化した本人識別情報の保存まで、実機で8.564秒で成功しました。これはFind Hubから位置を取得できたという意味ではありません。
- `https://www.googleapis.com/auth/android_device_manager` はGoogle Cloud側で無効と拒否され、実際のGoogle認証でも400 `invalid_scope` になりました。このスコープは製品の認可要求から削除しました。
- GMSから正規形式のMergeSession URLが返りましたが、新しいプライベートWebViewプロファイルで読み込むとGoogleのログイン画面へ戻りました。リダイレクトを止めた診断でもHTTP 302でAddSessionへ移り、認証済みWebセッションは確認できませんでした。URLの発行だけではSSO成功と数えません。
- 利用者がアプリ内で2段階認証した後に `accounts.youtube.com` へ移って止まることを確認しました。GoogleのSetSID / CheckCookieの経路を指定ホスト・指定パスだけ許可するよう修正しました。接続を再試行するときに、完了済みのWebログインをメタデータ未保存だけを理由に削除する処理も取り除きました。
- **修正後の実端末一覧・位置の取得、再起動後の再取得、オフライン位置のPIN認証は、まだ完了確認していません。** ブラウザで見えた端末やfixtureの成功を、このアプリの取得成功とは扱いません。

## 実装とテスト

GoogleのWebViewは非公開の `:google` プロセスで、データディレクトリsuffixは `googleFindHub` です。AppleとはCookieが分離されています。本人識別情報・接続メタデータは専用のKeystore鍵で暗号化し、GoogleのCookieはアプリ専用のWebView領域に保持します。WebViewのCookieデータベース自体をKeystore暗号化したとは主張しません。

DOMはMutationObserverとmicrotaskで処理し、HTMLの固定待機やフレーム待ちのポーリングを使いません。失敗期限だけをタイマーで設けます。カタログが返ったら位置取得完了を待たずにネイティブ画面へ戻ります。表示されていないときはbound refreshを止めます。

fixtureではslotと位置の照合、緯度0、無効な座標、相対時刻、本人確認への引き継ぎ、危険な操作への入力遮断、非表示WebViewでrAFが動かない場合の完了、複数アカウントのURL、後から届くアカウント情報の通知を確認します。実アカウントの認証画面の操作は利用者に任せます。

## 一次資料

- [Androidの認可ガイド](https://developer.android.com/identity/authorization)
- [AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
- [パッケージ・署名の登録](https://developers.google.com/android/guides/client-auth)
- [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)
- [AOSP BrowserのDeviceAccountLogin](https://android.googlesource.com/platform/packages/apps/Browser/+/refs/heads/main/src/com/android/browser/DeviceAccountLogin.java): 旧 `weblogin:` 経路を調べた一次ソース。現行Find Hubへの接続を保証する資料ではありません。
