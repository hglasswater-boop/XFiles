# SMB と WireGuard のオンデマンド連携（Issues #227 / #229）

## 目的と対象

モバイル版 XFiles の保存済み SMB 接続ごとに任意の WireGuard トンネル名を関連付ける。NAS に直接接続できない場合、公式 WireGuard アプリへトンネルの起動を要求する。**宅内の物理 Wi-Fi で NAS を直接検証できた場合は、指定トンネルの停止を要求する**。VPN の設定・鍵管理・パケット転送機構は XFiles には実装しない。Google TV 版では利用者向け設定・動作保証を設けない。

「宅外」は SSID や GPS で断定しない。SMB 接続に WireGuard 連携が有効な場合は、**SMBJ の失敗を待たず、最初に短時間 TCP（通常 700ms）の到達性確認を行う**。NAS に接続できれば SMBJ をそのまま起動し、到達不能なら VPN 起動後に TCP 接続を確認する。宅内でも NAS が停止している場合は VPN を起動する場合がある。IP アドレスの類似だけでは自宅かどうかは判定しない。

## セットアップ

1. Android に公式 WireGuard（パッケージ名 `com.wireguard.android`）をインストールし、家庭内の NAS に到達できるトンネルを作成・手動接続で検証する。NAS を VPN サーバーとしてインターネットへ SMB/445 を公開**しない**。
2. WireGuard の設定画面 → Advanced → **Allow remote control apps** をオンにする（第三者アプリによるトンネル制御権限に注意）。
3. XFiles の SMB サーバー追加/編集で「WireGuard（接続失敗時に自動起動）」を有効にし、WireGuard アプリで表示される**正確なトンネル名**を入力する。
4. 「WireGuard 操作権限を許可」から Android の `com.wireguard.android.permission.CONTROL_TUNNELS` を許可する。許可は自動接続の前提であり、XFiles が勝手に許可を得ることはない。
5. VPN 経由の NAS アドレスがルーティング可能であること、WireGuard の AllowedIPs に NAS のプライベートサブネット等が含まれることを確認する。`nas.local` の mDNS 解決は VPN 越しで失敗する場合があるため、必要なら固定 IP または VPN 側 DNS にする。

## 接続フロー

1. SMB 接続の設定はデフォルトで無効。連携無効時は既存コード経路を維持する。保存済み接続ごとに任意の `wireGuardTunnel`（空欄 = 無効）を持つ。
2. WireGuard 連携 ON の接続では SMBJ より**先に** TCP preflight を実行する。TCP 到達可能なら VPN を起動せず SMBJ を実行する。TCP 到達不能なら SMBJ のOS側タイムアウトを待たず指定トンネルに UP 要求する。SMBJ の接続・認証・共有選択・ファイル転送で失敗した場合には追加の UP 要求を行わない。連携 OFF の接続は従来の SMBJ 経路（事前 TCP 確認なし）を維持する。
3. TCP preflight が失敗したときのみ、公式 WireGuard の exported receiver 宛てに `com.wireguard.android.action.SET_TUNNEL_UP` / `tunnel` extra を送る。マニフェストに `com.wireguard.android.permission.CONTROL_TUNNELS` を宣言する。実行時に必要な許可がない場合は明示的なエラーにする。
4. 送信成功だけではトンネルが ON になったと断定できない。最大約 8 秒、指定 SMB ホスト:ポートへの TCP 到達性を短周期で確認し、到達した場合だけ SMBJ 接続を**1 回**実行する。認証失敗はそのまま通知する。
5. 並列操作では同一トンネルへの短時間の多重起動要求を抑制する。ネットワーク全断、アプリ未インストール、許可未付与、トンネル名不一致、WireGuard 側リモート制御 OFF、ルート未設定では、原因を案内して失敗終了する。再試行ループは禁止する。
6. 宅内での自動 OFF：連携が有効な SMB 接続を開くときだけ、Android `ConnectivityManager` の利用可能な**VPN ではない物理 Wi-Fi `Network`** に個別ソケットをバインドし、設定した NAS ホストの TCP ポートに実際に到達できるか確認する。到達できれば、`activeNetwork` がVPNかどうかに関係なく、指定トンネル名へ `com.wireguard.android.action.SET_TUNNEL_DOWN` / `tunnel` extra を送信する。DOWN は冪等な要求として扱い、同じトンネルへの短時間の重複送信を抑える。**同じ IP サブネットであることは必須ではない**（自宅内ルーティングされた別サブネットを含める）。物理 Wi-Fi がない/宛先 TCP に到達できない/ネットワークAPIが利用できない場合は OFF しない。停止指示の失敗は LAN 上の SMB アクセスを妨げない。
7. 自動 OFF は XFiles から当該 SMB 接続を開く時だけ実施する。XFiles の終了・SMB 切断・背景の回線変更だけでは OFF しない。関係のない WireGuard トンネルや他社 VPN を停止しない。OFF 要求が非同期で処理されるため、直後の SMB 接続に失敗しても**同一接続試行中にUPし直さない**（切替の反復を防止）。

## 保存と移行

- `SmbConnectionConfig.wireGuardTunnel` をローカルの SMB 定義に平文の「トンネル名」として保存する。VPN 秘密鍵は扱わない。
- 既存データ / 旧バックアップでは項目が存在しなければ空文字列と解釈し、連携は無効とする。
- 暗号化済み設定バックアップに同項目を追加する。バックアップ形式は後方互換のまま維持する。
- SMB 接続の複製時は関連するトンネル名を複製する。SMB 接続テストも同じ連携条件で動作させる。

## 対象外・制約

- Android OS でユーザーの操作なしに初めて VPN を構成・承認することはできない。別の常時接続 VPN がすでに有効な場合の切替は保証しない。
- WireGuard のブロードキャスト制御には応答 ACK がないので、失敗理由の完全な判別はできない。DOWN 通知も実際の停止確認ではない。
- VPN 使用中の物理 Wi-Fi への個別ソケット経路は Android の制約（VPN lockdown / OS 設定）で利用できない場合がある。その場合は安全側で OFF しない。
- IPアドレスとTCPポートの一致はNASの本人性の証明にはならない。外出先Wi-Fiで同じアドレスの別サービスが応答すると、誤って指定トンネルへ DOWN 要求する可能性がある。VPN非経由の物理 Wi-Fi でTCP到達することを必要条件とするが、厳密な識別にはSSID等の家庭固有情報やSMB認証が必要で、現時点では対象外。
- SMBJ の接続・認証・転送失敗を根拠に VPN を自動切替しない。接続開始前の TCP preflight が失敗したときだけ UP 対象。
- 現在の実装は WireGuard パッケージ `com.wireguard.android` のみ。WG Tunnel などの互換アプリには対応しない。

## テスト計画

| シナリオ | 合格基準 |
| --- | --- |
| 連携無効 | TCP 失敗をそのまま通知し、VPN UP/DOWN 要求ゼロ |
| VPN有効、Wi-FiからNASのTCP 445に直接到達 | 指定トンネルにDOWN要求を1回送信してからSMB接続 |
| VPN有効、物理Wi-Fiがない / NASに直接不達 | DOWN要求ゼロ、TCP preflight 不達なら即時UPと到達待機 |
| Wi-Fi回線とVPNが同時に存在、設定連携OFF | DOWN要求ゼロ、既存動作を維持 |
| DOWN送信の権限未許可 / 公式アプリ不在 | DOWN失敗でも SMB接続を妨げない |
| 同じNASへ連続アクセス | 短時間にDOWN要求を連発しない |
| 直接 TCP 到達 / 接続先ポートは開いているが SMB プロトコル交渉失敗 | WireGuard 要求ゼロ、従来の SMB エラーを通知 |
| TCP preflight 不達→ VPN 経由到達 | SMBJの長い接続失敗を待たずUPを1回要求、到達後 1 回 SMBJ接続 |
| TCP 不達→ 8 秒経っても不達 | リトライ停止、状態説明付きエラー |
| 認証ミス / 共有権限なし | VPN 起動要求ゼロ |
| 複数同時接続 | 短時間内の同トンネル起動要求が連発しない |
| 旧 SMB 定義 / 旧バックアップ | 連携 OFF として復元、既存設定維持 |
| 自宅 Wi-Fi / モバイル / 他 Wi-Fi / 機内モード | 実機で VPN 切替とリトライを記録 |
| VPN 起動済み / 許可未付与 / トンネル不存在 | 想定外の自動切断・無限待ちなし |
| split tunnel 等でデフォルト回線がVPN以外 | 物理 Wi-Fi から NAS 到達時には指定トンネルのDOWN要求 |
| 自宅のNASがWi-Fiと異なるサブネット | 同一サブネット制限なし、物理 Wi-Fi 直接TCP到達ならDOWN要求 |
| 連携なしSMB | 余分なTCP preflightやWireGuard操作なし |

開発手順: この資料を先に確定 → 失敗系を含むユニットテストを先に追加（TDD） → 必要最小限の実装 → CI と実機確認。
