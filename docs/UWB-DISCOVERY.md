# 探索広告とUWB設定の追加解析

2026-10-31。Androidアプリだけで標準「探す」のiPhone 17を探索するための継続調査です。iPhone側に別アプリを入れる構成は前提にしていません。実際の対象iPhoneのiOSバージョンは未確認です。

今回は取得済みの暗号処理から、BLE側の識別・探索設定へ調査を進めました。**所有者のトークン取得、GalaxyとのUWB接続、距離・方向の実測は未達です。** 実際の鍵・Keychain・アカウントDBは読まず、BLE広告・書き込み・測距・サウンドは開始していません。

## 取得物と範囲

| 対象 | バージョンと識別情報 |
| --- | --- |
| `nearbyd` | iPhone18,3 / iOS 27.0.1 / 24A446。SHA-256: `4f2702b9e7ab26ac9aab305f15a054965690d40cd6a0e29a6dacdd818d6f0dfa` |
| 今回取得した `bluetoothd` | 同じ公式IPSWの `/usr/sbin/bluetoothd`。13,092,096 bytes。SHA-256: `cfd94854d0a2efd3ca4a2de6e55349f43fd0d274a4b8bfa9937f29d9f7208cb1` |
| iOS `FMIPCore` | 同じDeviceSupportのarm64e。SHA-256: `eaaafb75d2f306b899b9e9687f7d1631a602993d934d07696f591840da7ea494` |
| macOS `FMIPCore` | macOS 27.2 / 26B123。LC_UUID: `C0CF2C81-633B-3CB1-AABE-8F32FF517EE4`、source version 470.21.6.16.43 |
| macOS `CoreBluetooth` | 同じmacOSの共有キャッシュ。LC_UUID: `A7D12D98-DD43-3355-AD22-FC974470FC1D`、source version 2701.7.1.0.0 |

IPSWは[Apple CDN](https://updates.cdn-apple.com/2026FallFCS/052dd27b-a831-4746-b00a-2e6a7f5a5586/iPhone18,3_27.0.1_24A446_Restore.ipsw)から必要なファイルを抽出しました。全IPSWをダウンロードして全体ハッシュを検証したという意味ではありません。抽出したbluetoothdは `codesign --verify --strict` が成功し、LC_UUIDは `8E49197B-E6BC-3F3B-A8D3-DF4BEDD37F01`、source versionは2700.51.1.3.0でした。抽出用イメージのマウントは終了後に残っていないことを確認しました。OSバイナリ・全シンボル・逆アセンブル結果はGitへ含めません。

以下は各取得物の静的仮想アドレスです。他のビルドへ固定アドレスを流用しません。macOS側の受信処理は別ビルドによる照合であり、iOSの実機通信キャプチャではありません。

## 1. FMIPRangingParametersはUWB接続鍵ではなかった

以前の調査で候補に挙げた `FMIPRangingParameters` の実際の保存フィールドを確認しました。

```text
struct FMIPCore.FMIPRangingParameters {
    let inBTRangeDistanceInMeters: Swift.Double
}
```

iOSの型記述子 `0x2624bd7f8` のフィールドは1つで、型のmangled name `Sd` は `swift-demangle` で `Swift.Double` と照合しました。macOSのSwiftメタデータでも同じ1フィールドです。この型にIRK、UWBアドレス、session key、peer discovery tokenはありません。名前だけを根拠に、Webの位置取得からUWB設定を取り出す経路とは扱いません。

DeviceSupportの切り出されたライブラリは、共有キャッシュ外の参照により汎用ツールの全型解析が途中で失敗しました。そこでローカルの相対メタデータだけを読む [inspect_swift_fields.py](../tools/inspect_swift_fields.py) を追加しました。未解決の型は推測せず `null` とし、画像を実行しません。

## 2. 探索の識別は2経路で異なる

`nearbyd` の `_getDiscoveryTokenFromCBDevice:advertisementType:` (`0x100008ee8`) は、内部広告種別 `0x13` と `0x1A` を別に処理します。

| 内部分類 | 読み取る情報と照合 |
| --- | --- |
| `0x13` / SpatialInteraction | `addressDataTxPreferred` と `spatialInteractionTokenData`。後者からNIDiscoveryTokenを構築する |
| `0x1A` / NearbyActionNoWake | `btAddressData` と3バイトの `nearbyActionNoWakeAuthTagData`。既知の候補トークンに対する計算結果と照合する |

`bluetoothd` のSpatialInteraction送信側 (`0x10005c894`〜`0x10005c8fc`) は、6バイトの広告アドレスと3バイトのtoken identifierを連結し、16バイトのIRKを鍵にSipHashを計算します。

```text
spatialIdentifier = uint64_be(SipHash-2-4(IRK, addressData[6] || identifierData[3]))[0:3]
```

戻り値へ `REV` を適用してから3バイトをコピーするため、big-endian側の先頭3バイトです。元の3バイトidentifierをそのまま送る経路とは区別します。

一方、`nearbyd` の `_getAuthTagForToken` (`0x1003df1e8`) のアドレスを使う分岐は次の形でした。

```text
noWakeAuthTag = uint64_le(SipHash-2-4(IRK, btAddressData))[0:3]
```

この分岐は下位24bitです。同じ関数には18バイトの `StaticSipHashInput` を使う別分岐もあります。この入力に終端NULは含みません。再現スクリプトでは両方を確認しましたが、対象の実機でどちらが選ばれるかは確認していません。無線上のアドレス表記とAndroidのMACアドレスAPIとのバイト順対応も、実キャプチャでは未検証です。

3バイトの識別タグ、以前確認した64bitのUWBアドレス計算、AES-CCM用の8バイトsession identifierを混同しません。24bitの識別タグには衝突もあり、一致するだけで対象の確定や所有者認証の完了とは扱いません。後段の相互認証と、その前提となる正規のトークン取得は別に必要です。

## 3. 5バイトの探索設定まで追跡

`bluetoothd` の `_updateSpatialInteractionPayload:` (`0x100175728`) が識別子と設定を連結する処理と、macOS `CBDevice._parseSpatialInteractionPtr:end:` (`0x1a6b35880`) の受信処理を照合しました。ここで扱うのはCoreBluetoothのSpatialInteraction本体で、BLE全体のmanufacturer headerやOPACK全体ではありません。

- 先頭flagsの下位2bitは、3バイト識別子の個数です。
- UWB設定を使うbit 3と、拡張形式のbit 7・設定有無のbit 6に応じて5バイトのUWB設定を読みます。bit 6だけで開始可否を決めません。
- 拡張形式のpresence情報には別のflagsとデータがあります。presenceだけの広告をUWB設定へ変換しません。

その5バイトを `nearbyd` の `NIServerSpatialInteractionPayload._parseUWBData:presenceData:` (`0x1002cbd64`) が次のように読みます。

| バイト | 読み取り |
| --- | --- |
| 0 | bit 0がfinding enabled |
| 1 | finding status |
| 2 | finding config |
| 3 | finding config 2 |
| 4 | このパーサーでは使わない。予約値やAndroid用の値と断定しない |

さらに `NIServerFindingAdvertisement.advertisementForFinder:…` (`0x1003e6210`) では、status bit 0が有効な場合に限り、Finderが送る広告はconfig、Findeeが送る広告はconfig 2から以下を取り出します。

| bit | 読み取り |
| --- | --- |
| 0〜2 | protocol version |
| 3〜4 | narrowband maskの内部コード |
| 5〜6 | MMS fragment数の内部コード |

Findeeの広告ではconfigが別途NB acquisition channel indexになります。status bit 1は `canRange`、bit 2は内部technology=1の対応情報、bit 4はuse caseです。これらのコードを、そのままAndroidのFiRa config ID・チャンネル番号・STS IVへ代入しません。

`startRangingWithPeer:…` (`0x1002ea880`) は広告のprotocol version、OOBのchannel index・mask・fragmentコードなどをサービス要求へ渡し、NBAMMS session managerへ接続します。広告のパース成功と、Galaxyが同じ要求を実行できることは別です。

## 4. 再現結果

[verify_uwb_discovery.py](../tools/verify_uwb_discovery.py) は固定の公開ダミー値だけを使い、実際の鍵やアカウントを引数に取りません。

| 検証 | 結果 |
| --- | --- |
| Spatialの9バイト入力・逆順化した3バイトタグをホストCoreUtilsと比較 | 一致 |
| NoWakeのアドレス分岐・固定文字列分岐をホストCoreUtilsと比較 | 両方一致 |
| Finder / Findeeで異なる設定位置 | ダミー値で確認 |
| 拡張presence / presenceのみの広告 | UWB設定の有無を分離 |
| 基本ダミー本体を0〜8バイトで切り詰め | 全9件を拒否 |
| 広告アドレスを変更 | 識別子が変わることを確認 |
| 従来のHKDF・CCM・SipHash再現スクリプト | RFC既知入力、64件のSipHash、32件の改ざん拒否を再確認 |

例としてIRK=`00…0f`、address data=`a0a1a2a3a4a5`、identifier=`b0b1b2`の場合、Spatial識別子は `e7e42e`、NoWakeのアドレス分岐は `eabec2` です。**すべてダミー値で、iPhoneから受信したデータではありません。**

```bash
python3 tools/inspect_swift_fields.py \
  "$HOME/Library/Developer/Xcode/iOS DeviceSupport/iPhone18,3 27.0.1 (24A446)/arm64e/Symbols/System/Library/PrivateFrameworks/FMIPCore.framework/FMIPCore" \
  --type FMIPRangingParameters --version-label 'iOS 27.0.1 24A446 arm64e'
python3 tools/verify_uwb_discovery.py --output research/uwb-2026-10-31/synthetic-discovery.json
python3 tools/verify_uwb_crypto.py --output research/uwb-2026-10-31/synthetic-crypto.json
```

## 5. トークン導出のbucket indexのバイト順

macOS 27.2 / 26B123のFindMyBase（LC_UUID `F1E60857-5AB6-3663-AFC7-6B44944BE945`、source version 106.21.6.16.7）の `Swift.FixedWidthInteger.data` getter (`0x1b40180e4`) を追跡しました。値をコピーした後、連続したメモリ範囲をDataに変換しており、バイト反転処理はありません。findmylocateagentのトークン生成側は、このgetterへUInt64の型情報を渡してHKDFのinfoに使用します。

静的解析に加え、ホストの同じgetterを**公開ダミー整数4個だけ**で呼び、Swiftで8バイトをコピーしたData表現と照合しました。0、1、`0x0102030405060708`、UInt64.maxの全件が一致し、例のinfoは `0807060504030201` です。ここから、確認したarm64ビルドでは次の表現と判定できます。

```text
info = uint64_le(bucket.index)             # 8 bytes
tokenSecret = HKDF-SHA256(sharedSecret,
                         salt="NITokenService.salt",
                         info=info, outputLength=16)
```

[verify_uwb_token_index.py](../tools/verify_uwb_token_index.py) と [C/Swiftヘルパー](../tools/uwb-token-index/) を追加しました。4種類のinfoによるHKDFもホストCommonCryptoと一致しています。ヘルパーは一時ディレクトリでビルドして終了時に削除します。private ABIが異なるOSへの適用は保証しません。iOSの同じgetterを実行した結果や、実際のトークン取得成功を意味しません。

```bash
python3 tools/verify_uwb_token_index.py --output research/uwb-token-index/verified.json
```

この検証で実アカウント・Keychain・デーモンへの要求・BLE・UWBは使用していません。入力sharedSecretも再現用の固定ダミー値です。bucketの有効期間、所有者の鍵取得、対象iPhoneとの接続は引き続き未確認です。

## 残る接続条件

1. 本人の権限で、対象iPhone用の共有鍵とdiscovery tokenをAndroid側だけから正規に取得・更新する経路。
2. WebデバイスID・既知トークン・実際の近傍広告を結び付け、対象側を適切な探索状態へ移す手順。
3. NBAMMSのNB/UWB設定・相互認証・開始要求と、Galaxyの無線実装との対応。
4. 正しい対象と両側のセッション成立、Androidの距離コールバックによる実測。

公開の[Android Ranging相互運用](https://developer.android.com/develop/connectivity/ranging#uwb-interoperability)はAppleアクセサリー用の設定交換を前提とし、[Appleの公開NI手順](https://developer.apple.com/documentation/nearbyinteraction/initiating-and-maintaining-a-session)も相手とのトークン交換を必要とします。今回解析した標準「探す」の開始方式と一致したとは確認していません。

今回は調査資料と、無線へアクセスしない再現ツールの追加です。製品APKへ探索開始ボタンや未確認の無線コマンドは追加していません。
