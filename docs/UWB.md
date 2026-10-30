# Android単体でのUWB探索

2026-10-30時点の調査です。要件はAndroidアプリだけを導入し、標準の「探す」に登録された本人のiPhoneなどと直接通信することです。iPhone側への連携アプリ導入を前提にしません。

## 確認できたこと

- Galaxy S26 Ultraの実機は `android.hardware.uwb` と `samsung.hardware.uwb` を公開しています。UWB非搭載が理由ではありません。
- Android 16以降の `RangingManager` はUWB対応状況のコールバックとraw rangingを提供します。iOSとの相互運用も記載されています。ただし相手の探索・接続とOOBの設定交換はアプリの責任です。[Android公式資料](https://developer.android.com/develop/connectivity/ranging)
- iOS相互運用ではsession ID、双方のUWBアドレス、channel/preamble、Vendor IDとStatic STS IV、slot durationなどが一致する必要があります。AndroidがUWBを送信できるだけでは、相手の測距セッションは開始されません。[同資料のiOS相互運用](https://developer.android.com/develop/connectivity/ranging#uwb-interoperability)
- Appleの公開Nearby Interactionでは、接続する両側がdiscovery tokenまたはアクセサリー設定を交換し、セッションを開始します。公開サンプルは相手側のアプリも実行する構成です。これは今回のAndroid単体という条件を満たしません。[Apple公式サンプル](https://developer.apple.com/documentation/nearbyinteraction/finding-devices-with-precision)

## 未解決の部分

標準の「探す」のiPhoneについて、次の情報をAndroid側だけで取得し、本人の権限で近距離探索を開始できる手順を確認できていません。

1. WebのデバイスIDと、近傍のBLE広告・ローテーションする識別子との対応。
2. 対象側が受け付ける認証済みBLE開始手順。
3. 対象側の測距セッションを開始するための設定・鍵・アドレスの交換。

現在の `initClient` / `refreshClient` を読む実装には、そのための経路がありません。公開NI仕様が標準「探す」の探索開始にも使えるという証拠もありません。不可能と証明したわけではなく、実装に必要な接続方式が未確認という状態です。

AirTagのBLE広告解析は別の仕組みで、iPhoneのUWB接続が成立した証拠にはなりません。[調査者のAirTag解析](https://adamcatley.com/AirTag)でもBLEとUWBは別に扱われています。

## ローカルのiPhone版・Mac版の静的解析

所有者のXcode DeviceSupportに取得済みの **iOS 27.0.1 / 24A446 / arm64e** のシステムフレームワークと、**macOS 27.2 / 26B123** の `/System/Applications/FindMy.app` を読み取りました。IPAのダウンロードはipatoolで試しましたが、既存App Storeセッションの照会と取得が応答せず、それぞれ時間制限で停止しました。IPAの取得成功とはしていません。パスワードの入力やKeychainからの鍵の抽出は行っていません。

| 対象 | 確認した名前・処理 | 分かる範囲 |
| --- | --- | --- |
| NearbyInteraction | `NIFindingConfiguration initWithRole:discoveryToken:preferredUpdateRate:` | 公開アクセサリーAPIとは別に、探索の役割とトークンを受け取る内部設定がある |
| NearbyInteraction | `NISession(Finding) generateFindingDiscoveryTokenWithSharedSecret:` | `0x24b354358` 付近で入力長を16バイトと比較し、不一致ならnilを返すことを逆アセンブルで確認 |
| NearbyInteraction | `NIDiscoveryToken(Finding_Project) generateFindingTokenWithIRK:` | `0x24b3586e4` 付近でIRKの長さを16バイトと比較する。任意のWebデバイスIDをそのまま渡す方式ではない |
| SPOwner | `SPLocalFindableConnectionMaterialMonitoringSession` / `peripheralConnectionMaterialForAccessoryIdentifier:completion:` | 接続情報を取得・監視するXPCクライアントがある |
| SPOwner | `SPPeripheralConnectionMaterial` の `irkData` / `btAddressData` / `btAddressWithTypeData` | Bluetooth識別と接続に必要な情報が別の構造体に分かれる |
| SPOwner | `SPBTFindingSession startFindingBeacon:completion:` と `startPlaySoundForBeacon:completion:` | Bluetooth探索と音の操作は別。解析中にどちらも実行していない |
| Mac FindMy | FMIPCoreの `rangingParameters` と `FMIPRangingParameters` の参照 | Macにも共通の測距関連コードへの参照がある。MacからUWB測距できるという意味ではない |

`NearbyInteraction` のSHA-256は `5143e393c23117978e14174fcf9763bde6f1cf32dd332c72219e1e2f23c6f8a1`、`SPOwner` は `cb67e3abf6e8c1c18f877135befd994c3169ac093c77b16f9b164cb92efd4322` です。アドレスはこの取得物に限定します。別ビルドで固定アドレスを使ってはいけません。

続いて、Apple CDNの同じ **iPhone18,3 / 27.0.1 / 24A446** のIPSWから `nearbyd`、`searchpartyd`、`findmydeviced` を抽出しました。取得には公式リリースのSHA-256を照合したipsw 3.1.739を使い、読み取り専用のイメージは抽出後に取り外されています。デーモンを実行したり、端末を変更したりしていません。

`nearbyd`（SHA-256 `4f2702b9e7ab26ac9aab305f15a054965690d40cd6a0e29a6dacdd818d6f0dfa`）のメタデータと逆アセンブルから、次の処理を確認しました。

- `NIServerFindingDiscoveryProvider` が `CBSpatialInteractionSession` などを使い、探索対象のトークンに対応する広告を扱います。
- `_FindingAdvertiser._configureSpatialInteractionAdvertiserWithPeer:advertisement:`（`0x1003df3bc`）はトークンの `getIRK` と `getSessionIdentifier` を読み、UWB設定を広告側へ渡します。不正なIRKを拒否する分岐もあります。
- `NIServerFindingAdvertisement.byteRepresentation`（`0x1003e6910`）は役割、6バイトのアドレス、status、payload、追加OOB情報などをOPACKにまとめます。これは内部の表現で、OPACK全体をそのままBLEの無線パケットとして送れることを意味しません。
- `NIServerFindingRangingProvider` には `NBAMMS` 用の設定を作る処理と、IRKからアドレスを求める処理があります。公開Android FiRa設定との対応は未確認です。
- `NIServerNBAMMSSession` はFinder/Findee双方のchallenge/responseと、相互認証の状態を持ちます。追加解析でHKDF-SHA256の入力、AES-CCMのエンベロープ、SipHash-2-4によるresponse生成まで追跡しました。実際の所有者の鍵は読み出していません。

追加調査のアドレス、入力形式、Mac版のトークン関連サービス、ダミーデータでの検証結果は [24A446の詳細記録](UWB-24A446.md) にまとめています。検証用の `tools/verify_uwb_crypto.py` は無線・アカウントへアクセスせず、暗号関数だけを比較します。この成功はiPhoneへのUWB接続成功を示しません。

探索開始に必要なコードの場所は絞れましたが、iPhoneアプリ本体の全解析、必要なトークンの正規の取得経路、無線上の完全な交換手順、GalaxyのUWB APIへの対応付けと実測は未完了です。`localFindable` にはPencilなどのアクセサリー用の処理も含まれるため、同名の経路をiPhone本体の鍵取得と見なしていません。`NIRangingAuthConfiguration` など他用途のクラスも、そのまま「探す」のプロトコルと見なしていません。

再現用の読み取りスクリプトは `tools/inspect_uwb_symbols.py` です。入力バイナリは実行せず、選択したシンボルとハッシュだけを出力します。

```bash
python3 tools/inspect_uwb_symbols.py \
  --ios-symbols "$HOME/Library/Developer/Xcode/iOS DeviceSupport/iPhone18,3 27.0.1 (24A446)/arm64e/Symbols" \
  --version-label 'iOS 27.0.1 24A446' \
  --mac-findmy /System/Applications/FindMy.app/Contents/MacOS/FindMy \
  --output research/uwb-ios27/report.json
```

入力OSバイナリ、全シンボルダンプ、逆アセンブル結果はGitに含めません。次に必要なのは、この接続情報の由来と対象側の開始処理を追い、本人のアカウントで取得できる範囲を確かめることです。

## 現在のアプリ

2026-10-31の継続調査では、BLE探索の2経路、識別タグの入力・バイト順、5バイトの探索設定を追加解析しました。候補だった `FMIPRangingParameters` はBluetoothの距離判定値だけを持つ型と確認しています。[探索広告とUWB設定の追加解析](UWB-DISCOVERY.md)に、取得物・アドレス・再現結果・残る条件を記載しています。

UWBの距離・方向表示は未実装です。確認済みの暗号処理はKotlinへ移植し、Galaxyの機能情報も公開APIのコールバックで取得しました。[Android移植とiPhone 17向けの追加記録](UWB-GALAXY.md)に結果を記載しています。GPSの位置、BLEのRSSI、MapLibreのカメラ中心をUWBの実測値として表示しません。今回の調査で対象へのBLE書き込みやサウンド送信は実行していません。

追加の接続方式を実装する際は、対象との対応が確認できること、両側でセッションが成立すること、Androidの実測コールバックが返ることを別々に検証する必要があります。UWB搭載の検出だけを探索成功には数えません。
