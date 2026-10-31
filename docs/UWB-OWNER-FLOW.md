# iPhoneの所有者鍵・R6仕様書・Galaxyの対応方式

2026-10-31。Androidアプリ単体からiPhone 17を探索するための追加調査と、無線を使わないKotlin実装です。**実際の所有者鍵の取得と、iPhone–Galaxy間のUWB測距は未達です。** iPhoneへ連携アプリを導入する方式へ変更していません。

## 調査対象

| 対象 | 識別情報 |
| --- | --- |
| iOS `findmylocated` | iPhone18,3 / iOS 27.0.1 / 24A446。SHA-256 `f783b5389a56dd6d7f22b65de1d3a475e13664e544d2667a5b2962dff468eb3d` |
| 同デーモンの署名・版 | `codesign --verify --strict` 成功。LC_UUID `3E2D2047-230B-3B3E-AD0F-1BFF2034C50D`、source version 141.30.6.14.13 |
| Mac `FindMyMessaging` | macOS 27.2 / 26B123の共有キャッシュから抽出。抽出物のSHA-256 `d0561af9ba11e27c8cddc8539968a58fe5caefaeca2219b25e6fd130600bea88` |
| Mac `CoreBluetooth` | [前回の記録](UWB-DISCOVERY.md)と同じ26B123。今回は受信パーサーを固定ダミー値だけで実行 |
| Galaxy | USB接続したSM-S948Q / SDK 37。`cmd uwb get-specification-info` などの読み取り |
| Appleの公式仕様書 | Nearby Interaction with UWB Interoperability Specification、Developer Preview、R6、全23ページ。表紙と本文・設定表を確認 |

iOSの実装名は `/usr/libexec/findmylocated` です。Macの `findmylocateagent` と同名だと仮定すると取得できませんでした。[Apple CDNの公式IPSW](https://updates.cdn-apple.com/2026FallFCS/052dd27b-a831-4746-b00a-2e6a7f5a5586/iPhone18,3_27.0.1_24A446_Restore.ipsw)から必要なファイルを抽出し、終了後に抽出用マウントが残っていないことを確認しました。

このOSの静的解析は、対象iPhoneが現在そのOSで動作していることの確認ではありません。Xcodeがconnectedと返したiPhone 17 / 27.2は `reality=simulated` のシミュレーターであり、実機測距の証拠には使っていません。OSバイナリ、仕様書PDF、個人の端末識別情報、逆アセンブル全文はGitへ含めません。

## 所有者鍵はデバイス上の乱数から作られる

iOSの作成処理 `0x10008c848` で、次の順序を確認しました。

1. `0x10008c8c8` で `FindMyCrypto.SharedSecretKey.length` を読む。
2. `0x10008c8cc` で `Data.random(bytes:)` を呼ぶ。
3. `0x10008c924` で `KeyRepresenting.init(rawValue:)` を呼ぶ。
4. `0x10008cf00` 以降に `Creating new OwnerSharedSecretsRecord` の記録がある。

この鍵の型の長さは32バイトです。`OwnerSharedSecretsRecord` の保存フィールドも、iOSでid・type・sharedSecret・baseDateと確認しました。WebのデバイスIDやパスワードから計算できる値だとは扱いません。別の乱数生成処理 `0x1004ad14c` は `addPeer(friend:)` に属するため、所有者鍵の作成とは区別します。

所有者向けの要求型 `OwnerTokenEnvelopeRequestV1` は空の構造体で、応答側 `OwnerTokenEnvelopeV1` にはidentifier・shareType・baseDate・sharedSecretがあります。要求にdevice IDを足せばHTTPで鍵を受け取れる、という構造ではありません。

## iOSでも配送先はLocalMessaging / IDS

| iOSの静的な箇所 | 確認した処理 |
| --- | --- |
| `0x10049b190` の受信処理 | `OwnerTokenEnvelopeRequestV1` とWatchからの所有者トークン要求に対応するログ |
| `0x10049bc04` | 要求の送信元を `LocalMessaging.device(fromID:)` へ渡してDeviceを解決 |
| `0x10049eab0` 以降 | `sendOwnerTokenIfNeeded(to:force:)`。送信済み端末への重複送信を避ける状態がある |
| `0x1004a035c` | ローカル端末へ所有者の共有鍵をIDSで送る処理のログ |

デーモンの静的entitlementには `com.apple.private.alloy.fmf.local` と、IDS messaging / registration / remote credentialsが含まれます。Macの `FindMyMessaging.LocalMessaging` にもIDSService・接続端末変更のコールバック等があり、Deviceには `isLocallyPaired`・`isConnected`・destination等があります。Macの `0x23bb7adbc` はIDSDeviceの `isLocallyPaired` を読み、`0x23bb7ae5c` でDeviceの対応フィールドへ保存します。

**Apple AccountへWebでログインできることと、このIDSサービスの正規の相手として参加できることは別です。** `force` が認証を迂回する引数だという根拠もありません。Androidから利用できる正規の登録・鍵配送は、まだ確認できていません。実アカウントのKeychain・DB・所有者鍵を読み出したり、Macを配送ブリッジにしたりはしていません。

## R6はFiRaアクセサリー用の設定交換

[Apple公式のiOS 27向けリンク](https://developer.apple.com/nearby-interaction/)から、本人が認証して保存したR6を確認しました。PDFのSHA-256は `3d661bab87ef9d9f342b8e602525b16732382ab124190944c914f4eafcc4bcac` です。

R6はFiRaのアクセサリーとの測距と、双方で設定データを交換する手順を説明しています。設定メッセージの版は2.2です。**標準「探す」の所有者鍵配送や、今回解析したNBAMMSの開始仕様ではありません。** 表4-2にはUWB session IDの型とサイズ欄に整合しない記載もあるため、その欄を推測して実装へ転用しません。

[Android公式のiOS相互運用](https://developer.android.com/develop/connectivity/ranging#uwb-interoperability)も、アクセサリープロトコルで交換したアドレス・session ID・チャンネル・Static STS等を合わせる方式です。今回の所有者用IRKをStatic STSの値へ代入すれば動く、という説明ではありません。

一方、24A446 `nearbyd` の `_prepareServiceRequestForDiscoveryToken:technology:…` (`0x1002edc2c`) は、technology=1とプラットフォーム側の条件が成立した分岐でNBAMMSのサービス要求を作ります。条件が不成立の分岐はoptionalを空にします。**この関数でFiRaへの代替開始は見つかっていません。** 他の用途・OS版まで不可能と証明したわけではありません。

## USB接続したGalaxyとの比較

今回の読み取りでも、公開される方式はFiRa・CCC・Aliroで、チャンネル9、UCI 3、FiRa PHY/MAC 1.3〜3.0、国コードJPでした。方位角の能力値は0です。[前回の公開APIコールバック](UWB-GALAXY.md)とも整合します。NBAMMSに対応する能力・設定項目は返っていません。

`persist.vendor.qti.uwb.soc=ganges` を読み取りました。FiRaの[WCN788xの認証情報](https://www.firaconsortium.org/certifications/certified-devices/wcn788x-core-4)のGANGESというファームウェア名と、リンク先の[FastConnect 7900](https://www.qualcomm.com/wi-fi/products/fastconnect/fastconnect-7900)は製品系列の手掛かりですが、実機からチップ品番WCN788xを直接読んだ結果ではありません。SoCはSM8850でした。

実機のシステムUWBサービス・HALの静的な文字列、SDK 37.0の `UwbRangingParams` も確認しました。公開設定はFiRaのDS-TWR系で、NBAMMSを選ぶ入口は確認できませんでした。文字列にないことだけで、シリコンの物理的な不対応までは断定しません。チャンネル9が同じでも、波形・認証・開始手順の互換性を意味しません。

またAppleは、日本でPeople FindingやApple Watchの精密探索などが利用できないと[説明しています](https://support.apple.com/en-tm/109512)。Galaxy側の国コードを変える対応は行いません。

## Kotlinへ追加した処理

| ファイル | 実装した範囲 |
| --- | --- |
| `FindingCrypto.kt` | 32バイト共有鍵からのbucket付きHKDF。8バイトlittle-endianのUInt64 indexを使用 |
| `FindingDiscoveryToken.kt` | IRKの所有コピー・終了時の消去、variant 1のraw OPACK、アドレス由来のSpatial / NoWakeタグ |
| `FindingTokenWindow.kt` | 26B123から読んだbucket境界モデル。実際の周期は呼び出し元に必須とし、iPhoneの値を仮定しない |
| `FindingAdvertisement.kt` | Androidが返すcompany ID除去済みmanufacturer dataのTLVとSpatial / NoWake本体の解析 |
| `FindingRadioAdvertisement.kt` | Finder / FindeeごとのNBAMMS設定コードの取り出し。FiRa設定へ変換しない |

受信パーサーの分岐表と呼び出し先を追い、無線上のSpatialInteraction typeは `0x13`、NearbyActionNoWakeは `0x1a` と確認しました。長さはヘッダー下位5bitです。`0x1c` は別用途で、Spatialとして扱いません。

[Objective-Cヘルパー](../tools/uwb-finding-token/advertisement_probe.m)は、固定ダミーパケット8件だけをMacのCBDeviceのメモリ内パーサーへ渡します。基本・拡張presence・presenceのみ・複数識別子・NoWake・他typeとの混在・別type・上位ヘッダービットを照合しました。全8件で独立したパーサーと一致しました。Bluetooth manager、スキャン、広告、接続、IDS送信、NISessionは作成していません。

Kotlinは欠けたパケットを拒否します。Appleの部分的なエラー状態の保持まで再現するものではありません。24bitのタグ一致も、所有者認証や対象の確定とは扱いません。Android MAC文字列とCoreBluetooth address dataのバイト順の対応は、実機通信で未確認です。

## 検証と未解決点

```bash
python3 tools/verify_uwb_advertisements.py --output research/uwb-advertisements/verified.json
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

- JVMの単体テスト38件が成功。新規12件はHKDF・OPACK・タグ・bucket境界・広告の既知入力と不正入力を検証します。
- Macの実受信パーサーとの比較はダミー値8件で成功。無線で受信したデータではありません。
- DebugビルドとLint成功。Lintは既存の警告46件、エラー0件で、今回のFinding関連ファイルに警告はありません。
- 実機ではUWB能力とシステム実装を読み取っただけです。実際のサウンド、紛失・消去、BLE書き込み、UWBセッション開始は実行していません。

次に必要なのは、対象iPhoneへ結び付く所有者鍵をAndroidへ正規に配送する方法と、相互認証後のNBAMMSをGalaxyの無線実装から実行できることの確認です。この2点を満たす経路はまだ得られていません。純粋関数と広告パーサーは製品の探索UIへ接続せず、距離や方向を表示する機能として配布しません。
