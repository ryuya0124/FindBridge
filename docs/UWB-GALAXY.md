# iPhone 17向けの追加調査とAndroidへの移植

2026-10-30。対象はユーザー指定のiPhone 17です。iOSバージョンは未確認です。iPhone側に連携アプリを導入する構成は前提にしていません。

## Galaxy実機で確認した情報

SM-S948QのUWBサービス情報と、Androidの公開 `RangingManager` の機能コールバックを読み取りました。測距セッションの作成・開始はしていません。

| 公開機能情報 | 実測値 |
| --- | --- |
| 距離測定 | true |
| 方位角 / 仰角 | false / false |
| チャンネル | 9 |
| configuration IDs | 1, 2, 3, 4, 6 |
| preamble indices | 9–12、25–32 |
| slot duration IDs | 2, 1。単位付きの時間値ではなくAPIのID |
| 8バイトMACアドレス | サービスの仕様情報で対応 |
| 仕様情報のプロトコル | FiRa、CCC、Aliro |
| FiRa PHY / MAC | 最低1.3、最高3.0 |

コールバックを使う検証は約0.085秒で完了しました。正常系で固定待機やポーリングはしていません。`verification/UwbCapabilitiesProbeTest.kt` には失敗判定用の10秒の期限だけがあります。

この結果から、現状の公開APIで距離測定は使えますが、方角を得られるとは扱えません。物理的にアンテナがないと断定する結果でもありません。Samsung独自サービスにはアンテナ設定とUCIコマンドの経路がありますが、その送信には `WRITE_SECURE_SETTINGS` が必要で、Apple NBAMMS互換モードは特定できていません。

読み取ったHALは `/vendor/lib64/hw/uwb_hal@impl-qti.so`、SHA-256は `802f2dd8bcf5d834f0b20f2f8b9e75f12bd90699293476d0822fcdcff71a5792` です。OSバイナリと逆コンパイル結果はGitに含めません。無線設定・国コード・校正値の変更は行っていません。

## Kotlinへ移植した部分

`FindingCrypto.kt` に、[24A446の静的解析記録](UWB-24A446.md)で確認した処理を移植しました。

- 16バイトのIRKと8バイトのsession identifierからのHKDF-SHA256によるセッション鍵導出。
- Mac版の `NITokenService.salt` を使う16バイトの導出。bucket付き経路は未実装。
- SipHash-2-4によるアドレス計算と役割別challenge/response。
- AES-CCMのエンベロープ生成・認証付き復号。
- 毎回新しいsession identifierを作る送信コンテキスト。counterの増加、枯渇時の拒否、終了時の鍵配列の消去。

Galaxyの標準プロバイダーは `AES/CCM/NoPadding` に対応しており、6バイトの認証タグを指定した結果がMacのCommonCryptoと一致しました。外部の暗号ライブラリやネイティブライブラリをAPKに追加していません。

JVMではHKDF・SipHashの既知データを検証し、SipHashはCoreUtilsと照合済みの64件を使います。Androidエミュレータでは6件のテストが成功しました。内容は暗号文の一致、全バイトの改ざん・別の鍵・別のsession identifierの拒否、unsigned counter、ブロック境界、counter枯渇、終了後の拒否・鍵消去、独立したRFC 3610の既知入力です。

これは暗号処理の移植です。実際の所有者トークン取得、対象の識別、NBAMMSの無線接続、距離・方向の実測にはまだ接続していません。サウンド操作も含みません。

## iPhone 17の接続条件

Apple公式資料では、日本では拡張距離の精密探索、「人を探す」、Apple Watchからの精密探索が利用できないとされています。今回のGalaxyの実際の国コードもJPでした。Macの「人を探す」用コードの再現だけでは、対象iPhoneの測距開始を保証できません。[AppleのUWB提供状況](https://support.apple.com/en-tm/109512)

「自分のデバイス」「人」「持ち物」の開始経路は分けて調べます。Appleの機能一覧でも、紛失したiPhoneの位置表示と、人・AirTagの精密探索は同じ操作として扱われていません。[Appleの機能一覧](https://support.apple.com/ja-jp/126302)

国コードの変更を対応策にはせず、日本で利用できる方式と対象側の開始条件を調べる必要があります。公開AndroidのiOS相互運用資料も、設定が一致したAppleアクセサリーの方式を説明するもので、標準「探す」の所有者用鍵を提供するAPIではありません。[Android公式資料](https://developer.android.com/develop/connectivity/ranging#uwb-interoperability)

## 再実行

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest
adb -s emulator-5558 shell am instrument -w \
  -e class dev.findbridge.android.FindingCryptoAndroidTest \
  dev.findbridge.android.test/androidx.test.runner.AndroidJUnitRunner
```

別パッケージの読み取り専用ドライバーを使う場合は、対象APKと同じ署名で `verification` をビルド・インストールします。

```bash
adb -s DEVICE shell am instrument -w \
  -e uwbCapabilities true \
  -e class dev.findbridge.verification.UwbCapabilitiesProbeTest \
  dev.findbridge.verification/androidx.test.runner.AndroidJUnitRunner
```

機能情報の取得にはテスト中だけshellのRANGING権限を借り、終了時に解除します。製品APKの権限やシステム設定は変更しません。
