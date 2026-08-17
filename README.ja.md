<div align="center">

<img src="docs/assets/logo.png" width="104" alt="XFiles logo">

# XFiles

**X-plore の操作感を目指したオープンソース Android ファイルマネージャー** — 2 ペインのツリー表示、アーカイブをフォルダのように閲覧、アプリ管理、APK/AAB/XAPK のインストール、Root / Shizuku、SMB2/3 ネットワーク共有に対応し、Material 3 Expressive UI を採用しています。

[![Release](https://img.shields.io/github/v/release/hglasswater-boop/XFiles?include_prereleases&sort=semver&label=release)](https://github.com/hglasswater-boop/XFiles/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#ビルドと実行)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](#技術スタック)
[![Network](https://img.shields.io/badge/network-SMB2%2F3%20only-informational)](#権限とプライバシー)

[English](README.md) · **日本語** · [简体中文](README.zh-CN.md)

<img src="docs/assets/demo.gif" width="300" alt="XFiles demo">

<sub>OnePlus 7 Pro（Android 16）での実機キャプチャ。ファイルコピー → アプリマネージャー → Root ファイルシステムの閲覧。</sub>

</div>

---

## この fork について

このリポジトリは [Local1stDotApp/XFiles](https://github.com/Local1stDotApp/XFiles) をベースにした個人向け fork です。

上流版の設計や機能を土台にしつつ、日常利用で使いやすくするため、SMB 対応、表示カスタマイズ、動画プレイヤー改善、設定バックアップなどを追加しています。

### この fork で追加・変更した主な機能

#### SMB2 / SMB3 ネットワーク共有

- SMBJ を利用した **SMB2 / SMB3 ファイルシステム**を追加。
- 保存した SMB サーバーを通常の XFiles ツリー内に表示。
- 接続設定では以下を指定可能。
  - 表示名
  - ホスト名 / IP アドレス
  - 共有名
  - 開始フォルダ
  - ユーザー名
  - パスワード
  - ドメイン
  - ポート番号
- `share/path` 形式で入力した場合も、共有名と開始フォルダに分解して利用可能。
- SMB 接続の **追加 / 接続テスト / 編集 / 複製 / 削除**に対応。
- 保存した SMB パスワードは平文保存せず、**Android Keystore を利用して暗号化**。
- ローカル ↔ SMB、SMB ↔ SMB のコピー / 移動に対応。
- **同一 SMB サーバー・同一共有内の移動は SMBJ の rename を利用**し、Android 端末経由でファイル本体をコピーしない高速なサーバー側移動に対応。
- SMB 上の画像・動画サムネイルを表示。
- 動画は埋め込みジャケット / カバーアートを優先し、ない場合は黒画面を避けたフレームをサムネイルとして取得。
- SMB 上の動画を端末へ全ダウンロードせず、Media3 から**直接ストリーミング再生**。
- ランダムアクセス、read-ahead、シーク処理を調整し、大容量 NAS 動画の再生・シーク性能を改善。

#### フォルダごとの並び順

- グローバルな並び順とは別に、**フォルダ単位でソート条件を保存**可能。
- パンくずリストを長押しすると、そのフォルダのソート設定を開けます。
- 以下をフォルダごとに保持。
  - ソートキー
  - 昇順 / 降順
  - フォルダ優先表示
- 個別設定がないフォルダではグローバル設定へフォールバック。
- SMB のルートや各共有でもフォルダ単位のソート設定を利用可能。

#### ブラウザ表示のカスタマイズ

- サムネイルサイズを変更可能。
- ファイル名の表示行数 / 折り返し方法を変更可能。
- ツリーの見かけ上の最大インデント深度を変更可能。
- コンパクト / 標準 / メディア向けの表示プリセットを用意。
- ファイル名は 1 行 / 2 行 / 3 行相当のコンパクト表示や全文折り返しに対応。
- 深い階層でも横幅を圧迫しすぎないようツリーインデントを調整。
- 可変高さの行でもツリーガイド線が自然につながるよう改善。
- フォルダ行には直下の項目数を、**フォルダアイコン + フォルダ数 / ファイルアイコン + ファイル数**として分けて表示。
- フォルダを閉じた後も直前に取得した件数を表示。
- 行間や選択領域を見直し、1 画面により多くのファイルを表示できる高密度 UI に調整。

#### 検索機能の改善

- 検索結果でファイル名をより省略されにくく表示。
- 検索結果にパスを分かりやすく表示。
- 検索結果でも画像 / 動画サムネイルを表示。
- 動画には再生時間バッジを表示。
- 検索履歴に対応。
- 検索結果をタップしたとき、対象ファイルの位置までツリーを展開して移動する動作を改善。

#### コピー / 移動操作の改善

- 選択項目のコピー / 移動を押した瞬間に開始せず、**移動先を確認してから実行**する方式に変更。
- 移動先選択では、
  - 元ペインの現在フォルダ
  - 反対側ペインの現在フォルダ
  へすぐ移動可能。
- その後さらに下位フォルダへ移動してから「ここにコピー」「ここに移動」で確定。
- 誤ったペインやフォルダへの操作を防止。
- 削除 / 移動後の一覧更新を改善。
- 大量転送時のツリー表示も見やすくなるよう調整。

#### コンテキストメニューのカスタマイズ

- ファイル / フォルダの長押しメニューの**表示順を設定画面から変更**可能。
- 項目を上 / 下へ移動して好みの順序に並び替え可能。
- デフォルト順へリセット可能。
- メニュー行の余白を詰め、よりコンパクトに表示。

#### 動画情報表示

- ファイル詳細画面に動画の以下の情報を表示。
  - 解像度
  - FPS
  - 再生時間
  - コーデック
  - ビットレート
- 動画サムネイル右下に**再生時間をオーバーレイ表示**。
- 動画メタデータはキャッシュし、ローカル / SMB / Root の各ファイルに対応。

#### 動画プレイヤーの改善

- 動画左半分をダブルタップで **-10 秒**。
- 動画右半分をダブルタップで **+10 秒**。
- 右側を上下スワイプしてメディア音量を変更可能。
- Android のシステム音量パネルを開かずに操作可能。
- 連続ダブルタップなど、短時間に複数回シークした場合の挙動を改善。
- ドラッグによるシーク動作を調整可能。
- SMB 動画のシーク / 読み込み処理を高速化。
- 元からあるフレーム単位ステップ再生などの高機能プレイヤーもそのまま利用可能。

#### 設定のエクスポート / インポート

- この fork の設定を別端末へ移行できる**設定バックアップ / 復元**を追加。
- バックアップ対象には以下を含みます。
  - 通常のアプリ設定
  - お気に入り
  - ブラウザ表示設定
  - フォルダごとのソート設定
  - ファイル関連付け
  - コンテキストメニュー順序
  - Root / Shizuku 関連設定
  - SMB 接続設定
  - **SMB パスワード**
- バックアップファイル全体を **AES-256-GCM** で暗号化。
- 鍵はバックアップ用パスワードから **PBKDF2-HMAC-SHA256（200,000 回）**で導出。
- ランダム salt を利用。
- 復元時の SMB パスワードは、復元先端末の Android Keystore で再暗号化して保存。
- SMB 接続 ID も維持するため、SMB を参照するお気に入りを復元後も保持可能。

#### ビルド / CI の変更

- feature ブランチを含む任意のブランチで利用できる **Debug CI** を追加。
- 手動実行にも対応。
- APK ビルドとユニットテストを並列化し、Android のビルドキャッシュを活用して CI を高速化。
- Debug APK も安定した署名鍵を利用できるため、毎回別アプリとして扱われず**既存インストールへ上書き更新**可能。
- Debug APK のファイル名にアプリ名と `versionName` を使用。
  - 例: `XFiles-1.3.6-smb.apk`
- Release ビルドも GitHub-hosted runner 上で実行。
- 新しい push が入った場合は古い不要なビルドをキャンセル。

この fork では上記のように上流版から意図的に挙動を変更している箇所があります。不具合を比較するときは、まずこの一覧に該当する変更か確認してください。

## XFiles を使う理由

- X-plore に近い 2 ペイン・ツリー型の操作感。
- Root や Shizuku を扱えるオープンソースのファイルマネージャー。
- アカウント、広告、テレメトリなし。
- 必要な機能を自分で追加・調整できる。
- この fork では NAS / SMB を日常利用しやすいよう重点的に改善。

## ダウンロード

APK は [**Releases**](https://github.com/hglasswater-boop/XFiles/releases) から取得できます。

- **`vX.Y`** — `versionName` を更新したときに作成される安定版。
- **`nightly`** — `main` への push ごとに更新されるローリング prerelease。

現在の fork 用 `versionName` は `1.3.6-smb` です。

**Android 8.0（API 26）以上**が必要です。

初回起動時は「すべてのファイルへのアクセス」を許可してください。

## 主な機能

### 2 ペイン・ツリーブラウザ

X-plore のように左右 2 つの独立したペインを利用できます。

- 大画面では左右に並べて表示。
- スマートフォンではスワイプでペインを切り替え。
- フォルダは別画面へ遷移せず、ツリー内でその場展開。
- 各ペインは独立した現在位置を保持。
- アーカイブもフォルダと同様にツリー内へ展開可能。

### SMB2 / SMB3

保存した SMB サーバーはローカルストレージと同じツリー内に表示されます。

- 共有フォルダを閲覧。
- 開始サブディレクトリを指定。
- ローカル ↔ SMB 間でコピー / 移動。
- SMB ↔ SMB 間でもコピー / 移動。
- 同一共有内の移動はサーバー側 rename を利用。
- SMB 上の画像 / 動画サムネイルを表示。
- SMB 動画を直接ストリーミング再生。

### ツリー内サムネイル

ローカル / SMB の画像と動画をツリー上にサムネイル表示します。

動画は埋め込みカバーアートを優先し、ない場合は動画フレームを抽出します。取得した情報はキャッシュされ、NAS への不要なアクセスを減らします。

### ファイル操作

- コピー
- 移動
- 削除
- 名前変更
- 新規フォルダ
- ZIP 作成
- アーカイブ展開
- 競合時の Skip / Overwrite / Keep both
- 長時間処理のキャンセル

バックグラウンド処理エンジンにより、長時間のコピー / 移動 / 圧縮 / 展開でも進捗を表示できます。

### 高速 ZIP

ZIP 作成では複数 CPU コアを利用して並列圧縮します。

既に圧縮済みのメディアなどでは STORE を利用し、不要な再圧縮を避けます。展開も複数ワーカーで処理し、Zip-Slip 対策を行っています。

### フォアグラウンドサービス

長時間のコピー / 移動 / ZIP / 展開 / パッケージインストールは、アプリをバックグラウンドへ移動しても継続できます。

通知には進捗とキャンセル操作を表示します。

### アーカイブをフォルダとして閲覧

以下を読み取り専用のフォルダのように展開できます。

- zip
- jar
- apk
- 7z
- tar
- tar.gz
- tar.bz2
- tar.xz
- rar

### アプリマネージャー

インストール済みアプリ / システムアプリを一覧表示します。

- アプリ起動
- アンインストール
- APK をファイルとしてコピー
- version / package 情報表示
- Activities / Providers / Receivers / Services の確認
- `base.apk` と split APK の閲覧
- exported / enabled 状態の確認
- 対応可能なコンポーネントの有効化 / 無効化

### APK / AAB / XAPK インストール

以下の形式を端末上から直接インストールできます。

- `.apk`
- `.apks`
- `.apkm`
- `.xapk`
- `.aab`

XAPK の OBB 配置にも対応します。

AAB は内蔵した bundletool を利用して端末向け split APK へ変換します。

### Root / Shizuku

設定から Root アクセスを有効 / 無効にできます。

- **su** — rooted 端末で完全な superuser アクセス。
- **Shizuku** — root なしでも ADB shell 権限でアクセス。

`Android/data` や `Android/obb` など、通常アプリからアクセスしにくい場所も利用可能です。

読み取り専用モードも用意されており、誤操作を抑えながら privileged path を確認できます。

### ビューアー

- 画像ビューアー
- テキストビューアー / 編集
- Hex ビューアー
- オーディオプレイヤー
- Media3 / ExoPlayer ベースの動画プレイヤー

動画プレイヤーはフレーム単位ステップ、フレーム番号表示、時間 / フレーム基準のスクラブ、フルスクリーンなどに対応します。

この fork ではさらに ±10 秒ダブルタップ、右側音量スワイプ、連続シーク改善を追加しています。

### 検索

`*` / `?` ワイルドカードを利用した再帰検索に対応します。

アーカイブ内部も検索可能で、検索結果から元のツリー位置へジャンプできます。

### 他アプリから開く

設定で明示的に有効化すると、他アプリの「アプリで開く」から XFiles を選択できます。

- アーカイブ
- 画像
- 動画

デフォルトでは無効です。

### Material 3 Expressive

- Dynamic Color
- Light / Dark / System
- Edge-to-edge
- Floating toolbar
- Floating breadcrumb
- Expressive motion
- Wavy progress indicator

など、最新の Material 3 系 UI を利用しています。

### 多言語対応

システム言語に合わせて UI を切り替えます。

日本語、英語、中国語（簡体 / 繁体）、韓国語、フランス語、ドイツ語、スペイン語、ポルトガル語など複数言語に対応しています。

## 権限とプライバシー

XFiles にはテレメトリ、アカウント、広告はありません。

この fork では SMB 接続のため `INTERNET` 権限を追加していますが、ユーザーが明示的に設定した SMB サーバーへの接続に利用します。

| 権限 | 用途 |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | 共有ストレージ全体の閲覧 / 更新 |
| `READ_EXTERNAL_STORAGE` *(API 32 以下)* | 旧 Android のファイル読み取り |
| `WRITE_EXTERNAL_STORAGE` *(API 29 以下)* | 旧 Android のファイル書き込み |
| `QUERY_ALL_PACKAGES` | アプリマネージャーでインストール済みアプリを一覧表示 |
| `REQUEST_DELETE_PACKAGES` | アプリのアンインストール |
| `REQUEST_INSTALL_PACKAGES` | APK / APKS / APKM / XAPK / AAB のインストール |
| `POST_NOTIFICATIONS` | 長時間処理の進捗通知 |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | コピー / 移動 / インストールなどをバックグラウンドでも継続 |
| `WAKE_LOCK` | 長時間処理中のスリープ抑止 |
| **`INTERNET`** | ユーザーが設定した SMB2 / SMB3 サーバーへ接続 |

SMB 認証情報は端末内に保存され、パスワードは Android Keystore で保護されます。

設定バックアップには SMB 認証情報を含めることができますが、書き出す前にバックアップ全体をパスワードで暗号化します。

宣言されている権限は [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) で確認できます。

## 技術スタック

| レイヤー | 採用技術 |
|---|---|
| 言語 / UI | Kotlin, Jetpack Compose, Material 3 Expressive |
| Build | AGP 9.2.1, Gradle 9.4.1, compileSdk 37 / target 37 / min 26 |
| Architecture | MVVM + StateFlow、手動 DI composition root (`di/Graph`) |
| 設定保存 | DataStore Preferences、Android Keystore |
| SMB | SMBJ、SMB2 / SMB3、ランダムアクセス |
| メディア / 画像 | Coil 3、Media3 ExoPlayer |
| アーカイブ | java.util.zip、commons-compress、xz、junrar |
| Privileged access | Shizuku 13.1.5、`su` shell |
| パッケージインストール | PackageInstaller、bundletool 1.18.3、ARSCLib |
| 設定バックアップ | AES-256-GCM、PBKDF2-HMAC-SHA256（200,000 回） |

Material 3 Expressive API を利用するため、material3 は対応する alpha バージョンを使用しています。

## プロジェクト構成

```text
app/src/main/java/app/local1st/files/
├── core/
│   ├── fs/        Local / Archive / Apps / Root / SMB ファイルシステム
│   │   └── priv/  su / Shizuku privileged transport
│   ├── ops/       コピー / 移動 / 削除 / 圧縮、競合処理
│   ├── search/    再帰検索
│   ├── prefs/     設定、フォルダ別ソート、SMB、暗号化バックアップ
│   ├── thumb/     ローカル / SMB の画像・動画サムネイル
│   └── util/      MIME、Intent、パッケージインストール等
├── di/            Graph / GraphInit
└── ui/
    ├── browser/   2 ペインツリー、パンくず、行表示
    ├── components/ 共通 Compose UI
    ├── main/      MainScreen / MainViewModel
    ├── dialogs/   ファイル操作 / SMB / 各種ダイアログ
    ├── viewer/    画像 / テキスト / Hex / 音声 / 動画
    ├── search/    検索 UI
    ├── settings/  設定、表示、バックアップ
    ├── appinfo/   アプリ詳細
    └── theme/     Material 3 Expressive

vendor/bundletool-shaded/   bundletool と依存ライブラリ
```

## ビルドと実行

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

必要な環境:

- JDK 17 以上
- Android SDK Platform 37
- Android 8.0（API 26）以上の実機 / エミュレーター

初回起動時は「すべてのファイルへのアクセス」を許可してください。

## Releases / GitHub Actions

`main` へ push すると `.github/workflows/release.yml` が署名済み APK をビルドします。

- `versionCode` は GitHub Actions の run number を利用して増加。
- `versionName` は `version.properties` で管理。
- `versionName` が変わらない間は `nightly` prerelease を更新。
- `versionName` を更新すると新しい安定版 `vX.Y` を作成。
- 署名情報は GitHub Secrets から取得。

`.github/workflows/personal-fork-ci.yml` は feature ブランチや手動実行向けの Debug CI です。

## ライセンス

[GPL-3.0-only](LICENSE)

改変した XFiles を配布する場合も GPL-3.0-only の条件に従ってソースコードを提供してください。

---

*本プロジェクトは X-plore File Manager の操作性に着想を得た学習 / クローンプロジェクトです。X-plore のコードやアセットは使用していません。*
