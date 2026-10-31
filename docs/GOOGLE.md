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

- 専用Google CloudプロジェクトにAndroid OAuthクライアントを登録しました。パッケージは `dev.findbridge.android`、署名SHA-1は `8C:BE:28:75:35:54:59:B8:26:4F:8B:25:B6:E9:3D:B4:90:39:AA:79`。登録時はTesting設定でした。要求する基本スコープにはテスト利用者リストの例外があり、アプリ側にも開発者アカウント限定の制限はありません。[配布時の条件](DISTRIBUTION.md)を参照してください。課金・クラウド実行サービスは作成していません。
- 公開スコープのGMS認証、Google UserInfoの確認、暗号化した本人識別情報の保存まで、実機で8.564秒で成功しました。これはFind Hubから位置を取得できたという意味ではありません。
- `https://www.googleapis.com/auth/android_device_manager` はGoogle Cloud側で無効と拒否され、実際のGoogle認証でも400 `invalid_scope` になりました。このスコープは製品の認可要求から削除しました。
- GMSから正規形式のMergeSession URLが返りましたが、新しいプライベートWebViewプロファイルで読み込むとGoogleのログイン画面へ戻りました。リダイレクトを止めた診断でもHTTP 302でAddSessionへ移り、認証済みWebセッションは確認できませんでした。URLの発行だけではSSO成功と数えません。
- 利用者がアプリ内で2段階認証した後に `accounts.youtube.com` へ移って止まることを確認しました。GoogleのSetSID / CheckCookieの経路を指定ホスト・指定パスだけ許可するよう修正しました。接続を再試行するときに、完了済みのWebログインをメタデータ未保存だけを理由に削除する処理も取り除きました。
- 認証後にFind Hubページが読み込めても、端末一覧検出が0件のままになる状態を実機で確認。実DOMでは4個の `data-index` がクリック可能なボタンの親の行にあり、従来セレクターは0件と判定していました。行と内側の画像付きボタンを組にして読み取り、日本語の戻る操作にも対応しました。
- 修正後に実端末4台を取得し、ネイティブ画面へ戻ることを確認。さらに製品プロセスを再起動する明示的な読み取りテストで、保存済みセッションから実端末4台・3台の位置を取得し、読み込み終了とネイティブ表示を16.889秒で確認しました。利用者がログインを完了した後は、認証画面の自動操作をしていません。Googleが返す位置の時刻・鮮度やオンライン状態は推測しません。
- オフライン位置のPIN認証、OS再起動後の復元、長時間の運用は未確認です。

## 実装とテスト

GoogleのWebViewは非公開の `:google` プロセスで、データディレクトリsuffixは `googleFindHub` です。AppleとはCookieが分離されています。本人識別情報・接続メタデータは専用のKeystore鍵で暗号化し、GoogleのCookieはアプリ専用のWebView領域に保持します。WebViewのCookieデータベース自体をKeystore暗号化したとは主張しません。

DOMはMutationObserverとmicrotaskで処理し、HTMLの固定待機やフレーム待ちのポーリングを使いません。失敗期限だけをタイマーで設けます。カタログが返ったら位置取得完了を待たずにネイティブ画面へ戻ります。表示されていないときはbound refreshを止めます。

fixtureではslotと位置の照合、緯度0、無効な座標、相対時刻、本人確認への引き継ぎ、危険な操作への入力遮断、非表示WebViewでrAFが動かない場合の完了、複数アカウントのURL、後から届くアカウント情報の通知、親の行に端末番号が付く実機と同じDOM構造、日本語の戻る操作を確認します。実アカウントの認証画面の操作は利用者に任せます。

## 3.2.0-rc2の追加検証

実機の4台中3台で機種名・IMEI・初回登録日・充電状態・Wi-Fi名を取得し、3台の位置を含む一覧を16.763秒で読み取りました。「不明なデバイス」は返却されたPixel 9の機種名に置き換えます。メーカー・キャリア・MEIDはこの実機応答では確認できていません。取得できた項目だけを表示します。

fixtureでは3つの異なるメールドメインの本人識別と、別アカウントからの応答拒否を確認します。専用のテスト保存領域ではアカウント切り替え後の一覧破棄と旧アカウント応答の拒否も検証します。実ユーザー2人目のGMS初回認証は未確認です。3.2.0-rc2まではGoogleの遠隔操作を実装していませんでした。

## 一次資料

- [Androidの認可ガイド](https://developer.android.com/identity/authorization)
- [AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
- [パッケージ・署名の登録](https://developers.google.com/android/guides/client-auth)
- [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)
- [AOSP BrowserのDeviceAccountLogin](https://android.googlesource.com/platform/packages/apps/Browser/+/refs/heads/main/src/com/android/browser/DeviceAccountLogin.java): 旧 `weblogin:` 経路を調べた一次ソース。現行Find Hubへの接続を保証する資料ではありません。


## 3.3.0-rc1の追加範囲

サービスの操作コントロールが有効な端末に限り、サウンド再生・停止・紛失設定を試験実装しました。ネイティブの明示確認→単一使用チケット→対象・アカウントの照合→フォアグラウンドで1回の実行許可→公式ページの操作という経路です。Google公式アプリの署名を装う処理や非公開OAuthスコープの要求はありません。

チケットはMainプロセスのメモリだけに保持し、Intentにはランダムなチケットだけを渡します。同一UID限定のContentProviderが一度だけ内容を渡し、送信直前にも世代・アカウント・有効期限を照合します。アカウント変更・切断で失効し、閉じると破棄します。別ページへの移動後は自動で再送しません。資格情報の入力は本人へ渡し、同一ページ内の連絡先欄だけをネイティブで確認した値で埋めます。

`google-device-action.js` はGoogleの正規origin・メインフレーム・Find Hubのパスだけで動作します。端末slotと元の名前・アカウントからSHA-256の既存識別子を再計算します。対応コントロールの `jsname` は、取得済みの公式クライアントで確認した `gnWSqc`（再生／停止）、`LVPyJb`（紛失設定）、`HJT2Ge` / `vcCt7`（連絡メッセージ／電話）だけを扱います。Googleの別ページでの再認証、未知の安全確認、画面変更によって実行できない場合があります。成功を確認できなければ不明として返し、遠隔操作を再試行しません。

「暗号化された位置を確認」から選択した端末の読み取りと公式PIN確認へ進めます。PIN・復号鍵はブリッジへ渡しません。端末0台は、本人アカウントと明示的な空一覧メッセージが揃う場合だけ受け入れます。個別更新では対象外の端末の既存情報を保持します。

追加操作はネットワークを遮断したHTML fixtureで検証します。実際のGoogleサウンド、ロック、PINによるオフライン復号は今回も未検証です。[対応状況](FEATURES.md)と[検証記録](../VERIFICATION.md)を参照してください。

## 3.3.0-rc4の接続案内

GMSでの本人情報確認後は、「Googleへログイン済みです。Find Hubへ接続してください」とネイティブ画面で案内します。「Find Hubへ接続」は本人の操作で開始し、保存済みの専用Webプロファイルを先に試します。最初のFind Hub接続や、Googleが追加確認を要求する場合には公式画面の操作が必要です。GMSの基本本人情報のトークンをFind HubのセッションCookieと同一視しません。

すでにGMSの本人情報を保存している場合はアカウント選択をやり直しません。別のアカウントへの切り替えは明示操作で選べます。「自分」のApple用ログイン設定はAppleの欄へ移動し、Google欄ではFind Hubの接続状態を表示します。今回、実アカウントを使ったGMS・Find Hubの再ログインは検証していません。
