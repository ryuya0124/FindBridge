# 人・持ち物・Apple Pencil Proの追加連携

## 現在の到達点

Androidのネイティブ一覧・地図・詳細と、所有者が選ぶファイルの読み込みを実装しています。実際のAppleアカウントから人・持ち物・Pencil Proを自動取得する連携元は未実装です。アプリにサンプルの人や持ち物を常時表示する処理はありません。

AppleはAirTagとFind Myネットワークの持ち物を探す際、Appleデバイスの「探す」アプリを使うよう案内しています。iCloud Webのデバイス取得と同じ認証応答には、これらの一覧や復号鍵は含まれません。[Appleの説明](https://support.apple.com/en-us/104978)

Apple Pencil ProはAppleの説明ではiPadまたはiPhoneの「探す」から表示できます。このアプリでは連携データ中のPencilを「持ち物」から分けて「デバイス」に表示します。近距離探索は実装していません。[Appleの説明](https://support.apple.com/en-gb/guide/ipad/ipad445ff36d/ipados)

MacのFind Myキャッシュも調査しましたが、この開発環境のキャッシュと人のデータベースは暗号化されていました。復号鍵の抽出、SIP/AMFIの無効化、キーチェーンの変更、Mac常駐サービスの起動は実行していません。

## ファイル形式

「人を探す」「持ち物」の＋、または「自分」→「人・持ち物の連携」からJSONを選択します。以下は書式説明用であり、実データではありません。

```json
{
  "source": "所有者が設定した連携元",
  "people": [
    {"id": "unique-person-id", "name": "表示名", "kind": "Person", "location": null}
  ],
  "items": [
    {"id": "unique-item-id", "name": "表示名", "kind": "Tag", "location": null}
  ],
  "devices": [
    {"id": "unique-pencil-id", "name": "Apple Pencil Pro", "kind": "Pencil", "location": null}
  ]
}
```

位置がある場合は `location` に `latitude`、`longitude`、`timestamp`、必要に応じて `horizontalAccuracy` を指定します。時刻はUnix秒またはミリ秒です。座標は緯度±90、経度±180以内の有限値だけを受け付けます。片方の欠落は位置なしです。住所 `address`、所有者 `ownerName`、モデル `model` は任意です。Pencilはkind/model/nameから判別します。

最大4MB。少なくとも `people` / `items` / `devices` のいずれかの配列が必要です。ファイルの読取権限を保持し、更新時に同じファイルを再読込します。位置自体はアプリ内に永続化しません。ファイルの安全な保管・更新・削除は提供側で管理します。

## HTTPS接続の契約

対応する連携サーバーを別途用意する場合のクライアント仕様です。サーバー実装は同梱しておらず、実際のMacとの接続は未検証です。

```json
{
  "type": "findbridge.pairing",
  "server": "https://your-private-host.example",
  "token": "AT_LEAST_32_CHARACTERS_OF_RANDOM_SECRET",
  "fingerprint": "64_HEX_CHARACTERS_OF_SERVER_CERTIFICATE_SHA256"
}
```

クライアントは `GET /v1/snapshot` に `Authorization: Bearer ...` を付けます。応答は上記JSONです。OSの証明書チェーン検証とホスト名検証に加え、リーフ証明書のSHA-256を照合します。有効なHTTPS証明書が必要で、自己署名証明書の検証省略は行いません。リダイレクトは追跡せず、HTTP平文は許可しません。接続情報はKeystoreで暗号化します。証明書更新でピンが変わった場合は接続ファイルを入れ直してください。

設定を解除すると接続情報と取得済み情報を消し、進行中だった応答を画面へ反映しません。定期更新はアプリを表示中の30秒間隔だけです。現在の実アカウントで検証済みなのはAppleのデバイスと家族情報であり、この追加経路を通じた人・持ち物の実取得ではありません。
