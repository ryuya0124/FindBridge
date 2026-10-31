# 地図の見本画像

表示設定に同梱する見本は、実際の `MapStyles` をMapLibre Nativeで描画した画像です。端末やアカウントの位置を使わず、東京・築地付近（北緯35.665、東経139.770、ズーム14.4）の固定範囲を使用します。

| ファイル | 内容・出典 |
| --- | --- |
| `map_preview_standard.webp` | アプリの標準スタイル。OpenFreeMap / © OpenMapTiles / © OpenStreetMap contributors |
| `map_preview_standard_dark.webp` | 同じ範囲の標準ダークスタイル |
| `map_preview_aerial.webp` | 地理院タイル（国土地理院）のシームレス写真を加工した見本 |
| `map_preview_hybrid.webp` | 同じ写真へ標準スタイルの地名を重ねた見本 |

320×192px、WebP品質82で保存しています。設定画面ではこれらの画像を表示し、プレビュー用の追加地図を描画・通信しません。出典と加工した見本であることは選択カードの下にも表示します。

出典は[国土地理院の地理院タイル一覧](https://maps.gsi.go.jp/development/ichiran.html)、[国土地理院コンテンツ利用規約](https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html)、[OpenFreeMap](https://openfreemap.org/quick_start/)、[OpenStreetMapの著作権・ライセンス](https://www.openstreetmap.org/copyright)を参照してください。

## 再生成

API 37の空の検証環境にdebug版・androidTest版を用意した後、公開タイルへ接続できる状態で次のテストを明示実行します。実アカウントの認証や遠隔操作は行いません。

```sh
adb -s <emulator> shell am instrument -w \
  -e capturePreviews true \
  -e class dev.findbridge.android.MapPreviewCaptureTest \
  dev.findbridge.android.test/androidx.test.runner.AndroidJUnitRunner
```

アプリのcacheに生成される4ファイルを確認して `app/src/main/res/drawable-nodpi/` へ配置します。検証用にapplicationIdを変更した場合は、runnerとrun-asの対象パッケージも合わせて変更してください。通常のテスト実行ではこの生成処理をスキップします。
