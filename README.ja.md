<div align="center">

<img src="docs/assets/logo.png" width="104" alt="XFiles logo">

# XFiles

**ローカルストレージ、NAS 動画、パワーユーザー、Google TV までを一つの系統で扱う Android ファイルマネージャー。**

> **モバイルは2ペイン。TVはリモコン最適化。SMB2/3ストリーミング、動画ストーリーボード、Root/Shizuku対応。広告・テレメトリなし。**

[![Release](https://img.shields.io/github/v/release/hglasswater-boop/XFiles?include_prereleases&sort=semver&label=release)](https://github.com/hglasswater-boop/XFiles/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#ビルド)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](#技術スタック)
[![Network](https://img.shields.io/badge/network-SMB2%2F3-informational)](#プライバシー)

[English](README.md) · **日本語** · [简体中文](README.zh-CN.md)

<img src="docs/assets/demo.gif" width="300" alt="XFiles demo">

</div>

---

## この fork の売り

XFiles の X-plore 系ツリーブラウザを土台に、**NAS と動画を日常的に大量に扱う用途**へ重点的に拡張しています。SMB 共有をローカルと同じツリーから開き、動画を開く前に中身をストーリーボードで確認し、そのまま NAS から再生できます。モバイル版では同じ動画を Chromecast へ渡すこともできます。

さらに、タッチ操作とテレビのリモコン操作を無理に同居させず、**モバイル版と Google TV 版を別エディション**として最適化しています。Root / Shizuku、アーカイブ閲覧、アプリ管理、暗号化設定バックアップ、強力なファイル操作も同じアプリ系統にまとめています。

## 主な特徴

### NAS向け SMB2 / SMB3

- 保存した SMB サーバーを通常のファイルツリーへ直接表示。
- 標準では **Rust 製 SMB エンジンを優先**し、ネイティブ層が利用できない場合は SMBJ へ安全にフォールバック。
- 閲覧、コピー、移動、リネーム、サムネイル、ストーリーボード、動画再生まで SMB 上で直接処理。
- 同一共有内の移動は可能な場合にサーバー側 rename を利用。
- 大容量 NAS 動画向けにランダムアクセス、適応型 prefetch、ローリングキャッシュ、再生優先制御を調整。
- SMB パスワードは Android Keystore で保護。

### 動画を開く前に中身が見える

動画サムネイルをタップすると **ストーリーボード**を表示します。動画全体からフレームを順次生成してキャッシュし、まず画面に見えている範囲を優先して埋めます。

- フレーム数は **6〜120枚**、2枚刻みで設定可能。
- 最小間隔は **1〜10秒**で設定可能。
- フレームをタップすると、その位置から再生またはシーク。
- 長押しでより細かいタイムラインを表示。
- 通常プレイヤーと Chromecast 操作画面でも同じタイムラインを利用。
- 長尺動画でも冒頭だけに偏らず、動画全体を見渡せるようサンプリング。

### 動画を探すことまで考えたプレイヤー

- 映像と操作パネルの間に常時表示できるストーリーボード。
- レジューム位置を再生準備前に復元。
- 左右ダブルタップで **-10秒 / +10秒**。
- 右側上下スワイプでメディア音量調整。
- フレーム番号表示とフレーム単位ステップ再生。
- Picture-in-Picture で **-5秒 / +5秒**操作。
- 全画面、PiP、ストーリーボード展開時も映像やシークバーが重なりにくいレイアウト。

### モバイル版の Chromecast

モバイル版はローカル、SMB、ContentProvider 経由のメディアを一時的な HTTP Range relay で Chromecast へ渡せます。

- 再生 / 一時停止、シーク、前 / 次、プレイリスト操作。
- 連続シークをまとめて送信し、操作側は先に反応する低遅延 UI。
- SMB ハンドル再利用と事前ウォームアップでシークや動画切り替えを高速化。
- Cast 操作画面にもストーリーボードを表示。
- ファイル一覧でキャスト中の動画を強調表示。
- ファイラーへ戻っても下部ミニプレーヤーから操作画面へ復帰。
- 通知から前 / 次、±10秒、再生 / 一時停止。
- 動画切り替え時に前の動画情報が一瞬表示される Cast handoff 問題を状態管理で修正。

Chromecast は **モバイル版専用**です。Google TV 版はテレビ上で直接閲覧・再生する用途に絞り、Cast スタックを含めません。

### Google TV 専用エディション

XFiles TV は別パッケージとして、リモコン操作向けに最適化しています。

- Leanback ランチャー / バナー対応。
- 描画とフォーカスを単純化した **完全1ペイン**ブラウザ。
- 左右キーで現れるサイドアクションレール。
- ファイル行の Up / Down 移動とフォーカス復帰を明示制御。
- センターで再生 / 一時停止、左右で ±10秒、上で操作表示、下で非表示。
- Back は操作パネルを先に閉じ、その後プレイヤーを終了。
- TV 専用の更新フロー。

### 実用的なファイル操作

- モバイルは X-plore 系の2ペインツリーブラウザ。
- コピー、移動、削除、名前変更、新規フォルダ、ZIP、展開。
- コピー / 移動は移動先を確認してから実行。
- **1階層上へまとめる**機能で、直下サブフォルダの中身を親へ移し、空になったフォルダだけ削除。ローカルと SMB 接続ルートに対応。
- 競合時の Skip / Overwrite / Keep both。
- 長時間処理はフォアグラウンドサービスで継続。
- フォルダ別ソート、表示密度、サムネイルサイズ、コンテキストメニュー順序をカスタマイズ可能。

### 他アプリとの連携

- Android の `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 共有先として利用可能。
- 複数動画共有でも `video/*` など適切な MIME を維持。
- 外部アプリ向け `PICK_FILES` モードで、ローカル / SMB ファイルを一時的な読み取り URI として返却。SMB 認証情報は XFiles 内に保持。
- メディア処理アプリ向けに、ランダムアクセス、truncate、完了時コミットに対応したトランザクション型 SMB 出力ブリッジを用意。

### Root / Shizuku とパッケージ管理

- rooted 端末では `su`。
- root なしでは Shizuku による shell 権限アクセス。
- privileged path 用の読み取り専用安全モード。
- 権限が許す範囲で `Android/data`、`Android/obb` などへアクセス。
- インストール済みアプリ、split APK、コンポーネント情報を確認できるアプリマネージャー。
- `.apk`、`.apks`、`.apkm`、`.xapk`、`.aab` のインストールに対応。XAPK の OBB 配置にも対応。

### 設定移行とセルフアップデート

- **AES-256-GCM** と PBKDF2-HMAC-SHA256 を利用したパスワード暗号化設定バックアップ。
- 表示設定、お気に入り、フォルダ別ソート、関連付け、SMB 接続と保護された認証情報を移行可能。
- モバイル / TV とも GitHub Releases から自分のエディションに一致する署名済み APK を確認可能。
- 設定画面から自動確認、**今すぐ確認**、現在のバージョン / ビルド、最終確認日時、状態を確認可能。

## モバイル版とTV版

| | モバイル | Google TV |
|---|---|---|
| パッケージ | `app.local1st.files` | `app.local1st.files.tv` |
| ブラウザ | 2ペインツリー | 1ペイン、リモコン最適化 |
| 主な入力 | タッチ / ジェスチャー | D-pad / リモコン |
| Chromecast 操作 | 対応 | 非搭載 |
| ストーリーボード | ブラウザ / プレイヤー / Cast | TV UIで利用可能な動画機能 |
| セルフアップデート | 対応 | 対応 |

## ダウンロード

署名済み APK は [**GitHub Releases**](https://github.com/hglasswater-boop/XFiles/releases) から取得できます。

`v1.4.0-smb` などの正式版では:

- `XFiles-1.4.0-smb.apk` が通常のモバイル版。
- `XFiles-TV-1.4.0-smb.apk` が Google TV 版。
- Release CI ではモバイル AAB も生成します。

正式タグ作成後の `main` 更新では、ローリング prerelease の `nightly` が更新されます。

**Android 8.0 / API 26 以上**が必要です。

## 基本機能

上記の fork 独自機能に加えて、XFiles の基本機能も利用できます。

- ツリーをその場展開する X-plore 系ナビゲーション。
- ピンチズーム対応画像ビューアー。
- テキスト表示 / 編集、ページング Hex ビューアー。
- 音声プレイヤー、Media3 動画プレイヤー。
- ワイルドカード対応の再帰検索とアーカイブ内検索。
- ZIP / JAR / APK、7z、TAR 系、RAR のフォルダ風閲覧。
- 並列 ZIP 作成 / 展開。
- アプリマネージャーと Android パッケージインストーラー。
- Material 3 Expressive、Dynamic Color、edge-to-edge UI。
- 多言語 UI。

## プライバシー

XFiles には **アカウント、広告、テレメトリがありません**。ネットワーク通信はユーザーが明示的に利用する機能に使います。主な用途は SMB / NAS、モバイル版 Chromecast、更新確認です。

保存した SMB パスワードは Android Keystore で暗号化します。設定バックアップも書き出し前に暗号化します。宣言権限は [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml) と各エディションの Manifest で確認できます。

## 技術スタック

| レイヤー | 採用技術 |
|---|---|
| 言語 / UI | Kotlin, Jetpack Compose, Material 3 Expressive |
| Android | minSdk 26, compile/target SDK 37 |
| アーキテクチャ | MVVM + StateFlow、手動 DI composition root |
| SMB | Rust SMB2/3 エンジンを優先、SMBJ 互換フォールバック |
| メディア | Media3 ExoPlayer、Coil 3、モバイル版 Media3 Cast |
| 設定保存 | DataStore Preferences、Android Keystore |
| Privileged access | Shizuku、`su` |
| アーカイブ | java.util.zip、commons-compress、xz、junrar |
| パッケージ | PackageInstaller、vendored bundletool、ARSCLib |
| 設定バックアップ | AES-256-GCM、PBKDF2-HMAC-SHA256 |

Rust Android JNI は arm64-v8a、armeabi-v7a、x86_64 の3 ABIでシンボル検証してから通常 APK を組み立てます。

## ビルド

JDK 17+ と Android SDK platform 37 が必要です。

```bash
./gradlew :app:assembleMobileDebug :app:assembleTvDebug
```

出力:

```text
app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
app/build/outputs/apk/tv/debug/app-tv-debug.apk
```

Release CI は署名済みモバイル / TV APK とモバイル AAB を生成します。Debug CI はモバイル / TV のユニットテスト、両エディションの署名済みデバッグビルド、Rust JNI 検証に加えて、API 35 Android Emulator で起動とバックグラウンド / 復帰のスモークテストを実行します。

## この fork について

このリポジトリは [Local1stDotApp/XFiles](https://github.com/Local1stDotApp/XFiles) をベースにした個人向け fork です。元プロジェクトとコントリビューターの皆さまが公開した土台の上に、NAS / メディア、Chromecast、Google TV、ストーリーボード、更新、外部連携の機能を追加しています。

## ライセンス

[GPL-3.0-only](LICENSE)。変更版を配布する場合は、ライセンス条件に従って対応するソースコードも公開してください。
