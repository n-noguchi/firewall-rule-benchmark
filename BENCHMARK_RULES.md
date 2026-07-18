# マルチテナントL7ファイアウォール・ベンチマーク

## ルール定義・外部インターフェース仕様書

## 1. 目的

マルチテナントSaaS向けL7ファイアウォールにおいて、大量のアクセスログを優先順位付きFirst Matchルールへマッチングさせる処理性能を比較する。

比較対象はマッチングアルゴリズムだけに限定しない。

以下を含むシステム全体の構成を各ベンチマーク実装に委ねる。

* データベース製品
* テーブル設計
* インデックス設計
* DB接続方式
* キャッシュ方式
* 正規表現処理方式
* オンメモリデータ構造
* 並列化方式
* 実装言語
* ストアドプロシージャ
* DB内処理とアプリケーション処理の分担
* 派生テーブル、マテリアライズドビュー
* 読み取り専用の検索用データ構造

ローカルPC上のDocker環境で動作可能な範囲に限定する。

---

# 2. ベンチマーク部門

以下の2部門を設ける。

## 2.1 バッチ部門

大量のアクセスログを一括で処理し、全件の判定結果をCSVファイルとして出力する。

比較指標は、ユーザーがバッチ処理を開始してから、結果CSVファイルが完全に出力されるまでの経過時間とする。

## 2.2 逐次部門

アクセスログを1件ずつHTTP APIへ送信し、1件ずつ判定結果を返す。

同時実行数は1とする。

前のリクエストの応答を完全に受信してから次のリクエストを送信する。

比較指標は以下とする。

* 全アクセスログの処理完了までの合計時間
* p50レイテンシ
* p99レイテンシ
* 最大レイテンシ

主順位は全件処理時間とする。

---

# 3. 計測対象外

以下は性能比較時間に含めない。

* Dockerイメージのビルド
* コンテナ起動
* データベース起動
* 初期スキーマ作成
* マスタデータの初期ロード
* インデックス作成
* 正規表現の事前コンパイル
* キャッシュ構築
* 読み取り用派生データの構築
* JITウォームアップ
* 接続プール初期化
* ヘルスチェック待機

ベンチマークは、システムが処理可能なREADY状態になった後に開始する。

---

# 4. 計測対象

処理開始後に発生する以下の処理は計測に含める。

* APIまたはバッチ要求の受け付け
* アクセスログの読み込み
* JSONまたはファイルの解析
* DB接続
* DBクエリ
* キャッシュ参照
* ポリシー選択
* IPアドレスグループ解決
* ルール評価
* 正規表現評価
* 結果生成
* 通信
* CSV出力
* 出力のflush

---

# 5. テナント・部署の識別

アクセス対象の完全URLパスは以下の形式とする。

```text
/{tenant_id}/{department_id}/...
```

例：

```text
/tenant-00001/department-00010/api/users/123
```

`tenant_id` と `department_id` は、URLパスの先頭2ディレクトリから取得する。

入力データに別カラムとして保持してもよいが、ルール評価対象となるURLパスはテナントIDと部署IDを含む完全パスとする。

URL正規化、URLデコード、パストラバーサル対策などは本ベンチマークの対象外とする。

---

# 6. ポリシー選択

ファイアウォールポリシーはテナント単位または部署単位で設定できる。

評価時は以下の順序でポリシーを選択する。

1. 対象部署のポリシーが存在する場合、部署ポリシーを使用する
2. 対象部署のポリシーが存在しない場合、テナントポリシーを使用する
3. 部署ポリシーとテナントポリシーは結合しない
4. 部署ポリシーが存在する場合、テナントポリシーにはフォールバックしない

部署ポリシーが存在するがルールが0件の場合も、部署ポリシーのデフォルトアクションを使用する。

「部署ポリシーが存在しない状態」と「部署ポリシーは存在するがルールが0件の状態」は区別する。

---

# 7. ポリシー定義

論理データモデルは以下とする。

```text
FirewallPolicy
├─ policy_id
├─ tenant_id
├─ department_id nullable
├─ default_action
├─ enabled
├─ created_at
└─ updated_at
```

`department_id` がNULLの場合はテナントポリシーを表す。

同一テナント・同一部署に複数のポリシーは存在しない。

推奨一意制約：

```text
UNIQUE(tenant_id, department_id)
```

`default_action` は以下のいずれかとする。

```text
ALLOW
DENY
```

---

# 8. First Match評価

選択された1つのポリシー内で、ルールをpriorityの昇順に評価する。

```text
priority ASC
```

`priority` は同一ポリシー内で一意とする。

推奨一意制約：

```text
UNIQUE(policy_id, priority)
```

評価処理は以下とする。

```text
policy = selectDepartmentOrTenantPolicy(access)

for rule in policy.rules ordered by priority:
    if match(rule, access):
        return rule.action, rule.rule_id, policy.policy_id

return policy.default_action, null, policy.policy_id
```

最初に一致したルールで評価を終了する。

それ以降のルールは評価しない。

どのルールにも一致しなかった場合、選択されたポリシーの`default_action`を使用する。

---

# 9. ルール構造

1ルールには1種類の条件だけを設定する。

複数条件のAND結合やOR結合は行わない。

複数の条件を設定したい場合は、別々のルールとして定義する。

論理データモデル：

```text
FirewallRule
├─ rule_id
├─ policy_id
├─ priority
├─ rule_type
├─ action
├─ enabled
├─ regex_pattern nullable
├─ source_ip_group_key nullable
├─ start_time_utc nullable
├─ end_time_utc nullable
├─ created_at
└─ updated_at
```

アクションは以下の2種類とする。

```text
ALLOW
DENY
```

---

# 10. ルール種別

以下の6種類を定義する。

```text
SOURCE_IPV4_REGEX
SOURCE_IPV4_GROUP
URL_PATH_REGEX
ACCESS_TIME_RANGE
REFERER_REGEX
USER_AGENT_REGEX
```

## 10.1 SOURCE_IPV4_REGEX

アクセス元IPv4アドレスの文字列に正規表現を適用する。

IPv6は対象外とする。

例：

```text
入力:
192.168.10.25

正規表現:
^192\.168\.10\.[0-9]{1,3}$
```

IPv4としての構文的妥当性や値域はルール評価時には検証しない。

ベンチマークのアクセスログには正常なIPv4文字列を使用する。

## 10.2 SOURCE_IPV4_GROUP

ルールが参照するIPアドレスグループに、アクセス元IPv4アドレスが完全一致で含まれている場合に一致とする。

1ルールが参照できるIPアドレスグループは1つだけとする。

## 10.3 URL_PATH_REGEX

テナントIDと部署IDを含む完全URLパスに正規表現を適用する。

例：

```text
入力:
/tenant-001/department-010/api/users/123

正規表現:
^/tenant-001/department-010/api/users/[0-9]+$
```

## 10.4 ACCESS_TIME_RANGE

アクセス時刻のUTC時刻部分だけを評価する。

通常の時間帯は半開区間として扱う。

```text
start_time <= access_time < end_time
```

例：

```text
09:00:00 <= access_time < 18:00:00
```

日をまたぐ時間帯も許可する。

例：

```text
start_time = 22:00:00
end_time   = 06:00:00
```

評価式：

```text
access_time >= 22:00:00
OR
access_time < 06:00:00
```

`start_time == end_time` は不正なルールとし、データセットには含めない。

## 10.5 REFERER_REGEX

Referer文字列全体に正規表現を適用する。

Refererが存在しない場合は空文字として評価する。

```text
""
```

ヘッダー欠損を表す正規表現例：

```text
^$
```

## 10.6 USER_AGENT_REGEX

User-Agent文字列全体に正規表現を適用する。

User-Agentが存在しない場合は空文字として評価する。

---

# 11. 正規表現仕様

正規表現はRE2互換に制限する。

対象ルール：

```text
SOURCE_IPV4_REGEX
URL_PATH_REGEX
REFERER_REGEX
USER_AGENT_REGEX
```

一致方式は部分一致相当とする。

例：

```text
pattern:
Chrome

input:
Mozilla/5.0 Chrome/150

result:
match
```

完全一致が必要な場合は、ルール作成者がアンカーを指定する。

```text
^pattern$
```

RE2でサポートされない以下の機能は使用不可とする。

* 後方参照
* 先読み
* 後読み
* 再帰正規表現
* 条件付きパターン
* バックトラッキング依存機能

正規表現エンジンの製品やライブラリは自由とするが、RE2互換の一致結果を返すこと。

---

# 12. IPアドレスグループ

IPアドレスグループはファイアウォールルールとは別データとして管理する。

ルールとIPアドレスグループは独立して更新される。

## 12.1 グループ定義

```text
SourceIpGroup
├─ group_id
├─ tenant_id
├─ department_id nullable
├─ group_key
├─ name
├─ created_at
└─ updated_at
```

`department_id` がNULLの場合はテナントグループを表す。

推奨一意制約：

```text
UNIQUE(tenant_id, department_id, group_key)
```

## 12.2 グループメンバー

```text
SourceIpGroupMember
├─ group_id
├─ source_ipv4
├─ created_at
└─ updated_at
```

推奨一意制約：

```text
UNIQUE(group_id, source_ipv4)
```

仕様：

* 1グループに登録できるIPv4数に仕様上の上限は設けない
* ベンチマークデータ内では有限件数とする
* グループ内のいずれか1件と完全一致すれば一致
* 同じIPv4を複数のグループへ登録できる
* 同一グループ内の同一IPv4重複は許可しない
* ルールは物理的な`group_id`ではなく`group_key`を参照する

---

# 13. IPアドレスグループの部署優先

IPアドレスグループもテナント単位または部署単位で設定できる。

部署にIPアドレスグループ設定が存在する場合、部署側のグループセットを使用する。

部署側にグループ設定が存在しない場合、テナント側のグループセットを使用する。

部署グループとテナントグループは結合しない。

```text
部署のIPグループ設定が存在する
  → 部署グループのみ使用

部署のIPグループ設定が存在しない
  → テナントグループを使用
```

部署グループセットが選択された場合、同じ`group_key`が部署側に存在しなくてもテナント側へ個別フォールバックしない。

ルールが参照する`group_key`が、選択されたグループセットに存在しない場合、そのルールは不一致とする。

---

# 14. データ規模

想定テナント数：

```text
20,000テナント
```

最大規模テナント：

```text
20テナント
```

最大ルール数：

```text
1テナントあたり250,000ルール
```

最小ルール数：

```text
1テナントあたり10ルール
```

最大規模テナント20件だけで、最大約500万ルールとなる。

```text
20 × 250,000 = 5,000,000
```

全ルールをメモリに載せる方式、必要なテナントだけロードする方式、DB上で直接評価する方式など、実装方式は自由とする。

---

# 15. マスタデータの保守性要件

ユーザーが管理するマスタデータは、マスタメンテナンスアプリケーションから随時変更できる必要がある。

一括インポート機能は必須としない。

少なくとも以下の操作を個別に実行できること。

## 15.1 ポリシー

* テナントポリシー作成
* 部署ポリシー作成
* ポリシー更新
* ポリシー削除
* デフォルトアクション変更
* 有効・無効変更

## 15.2 ファイアウォールルール

* ルール1件追加
* ルール1件更新
* ルール1件削除
* priority変更
* action変更
* rule_type変更
* 条件値変更
* 有効・無効変更

## 15.3 IPアドレスグループ

* グループ作成
* グループ更新
* グループ削除
* IPv4を1件追加
* IPv4を1件削除
* グループ名変更
* テナント・部署設定変更

メンテナンス方法は以下のいずれかとする。

* SQLによるINSERT、UPDATE、DELETE
* 文書化されたCRUD API

DB上のマスタデータを直接変更可能な構成であること。

---

# 16. 高速化用派生データ

マスタデータと検索用データを分離してよい。

例：

```text
マスタDB
  ↓
同期または非同期反映
  ↓
高速検索用データ
```

高速検索用データとして以下を利用してよい。

* オンメモリHashMap
* HashSet
* Trie
* Bitmap
* DFA
* コンパイル済み正規表現
* 独自バイナリ
* KVS
* 別DB
* マテリアライズドビュー
* 複製テーブル
* 検索専用インデックス

マスタ更新後に検索用データへ反映できること。

反映方式は自由とする。

* 同期更新
* 非同期更新
* CDC
* DBトリガー
* イベント通知
* ポーリング
* スナップショット差し替え
* 全再構築
* 差分更新

ルール更新頻度は数分ごとを想定する。

更新反映時間は今回の性能順位には含めないが、保守性確認項目として記録してもよい。

---

# 17. READY補助API

計測開始前に、システムが利用可能であることを確認する補助APIを必須とする。

## 17.1 エンドポイント

```http
GET /health/ready
```

## 17.2 正常レスポンス

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "status": "READY",
  "data_version": "dataset-001"
}
```

## 17.3 READY条件

以下が完了した状態でREADYを返す。

* DB起動完了
* マスタデータロード完了
* インデックス構築完了
* 派生データ構築完了
* キャッシュ構築完了
* 正規表現コンパイル完了
* 接続プール初期化完了
* 未処理の同期処理が存在しない
* 指定データバージョンが反映済み
* 逐次APIとバッチ処理を実行可能

READY API呼び出し時間は性能計測対象外とする。

---

# 18. 提供データ

主催側は以下を提供する。

```text
policies
firewall_rules
source_ip_groups
source_ip_group_members
access_logs
expected_results
```

入力データ形式は別途定義する。

開発用データでは正解データも参加者へ提供する。

正式計測時は、参加システムから正解データを参照できないようにする。

正解データをマッチング処理の入力、キャッシュ、事前計算に使用してはならない。

---

# 19. 共通判定結果

バッチ部門と逐次部門は、同じ論理結果を返す。

各アクセスログについて以下を返す。

```text
access_id
selected_policy_id
matched_rule_id
action
```

## 19.1 access_id

入力アクセスログの一意ID。

## 19.2 selected_policy_id

部署優先のポリシー選択後、実際に評価対象となったポリシーID。

## 19.3 matched_rule_id

First MatchしたルールID。

どのルールにも一致せず、ポリシーのデフォルトアクションを使用した場合はNULLとする。

## 19.4 action

以下のいずれか。

```text
ALLOW
DENY
```

---

# 20. 正解データ

正解データは以下のレイアウトとする。

```csv
access_id,selected_policy_id,matched_rule_id,action
access-000001,policy-100,rule-200,DENY
access-000002,policy-100,,ALLOW
access-000003,policy-301,rule-999,ALLOW
```

`matched_rule_id`が空の場合は、デフォルトアクションが適用されたことを表す。

正解判定では以下の4項目をすべて比較する。

```text
access_id
selected_policy_id
matched_rule_id
action
```

以下は不正解とする。

* actionは一致しているがmatched_rule_idが異なる
* matched_rule_idは一致しているがselected_policy_idが異なる
* デフォルトアクションと同じactionの別ルールへ誤って一致した
* 部署ポリシーではなくテナントポリシーを使用した
* 優先順位の後ろのルールへ誤って一致した
* 出力欠落
* 出力重複
* 入力に存在しないアクセスIDの出力
* 余分な結果の出力

正解率は100%を要求する。

1件でも不一致がある場合、その実装は性能順位の対象外とする。

---

# 21. 逐次部門API

## 21.1 エンドポイント

```http
POST /v1/firewall/evaluate
Content-Type: application/json
```

接続先例：

```text
http://benchmark-target:8080
```

## 21.2 リクエスト

```json
{
  "access_id": "access-000001",
  "source_ipv4": "192.0.2.100",
  "url_path": "/tenant-001/department-010/api/users/123",
  "access_timestamp_utc": "2026-07-18T07:30:45.123Z",
  "referer": "https://example.com/page",
  "user_agent": "Mozilla/5.0 ExampleBrowser/1.0"
}
```

## 21.3 リクエスト項目

| 項目                     | 型      | 必須 | 内容              |
| ---------------------- | ------ | -: | --------------- |
| `access_id`            | string | 必須 | アクセスログ一意ID      |
| `source_ipv4`          | string | 必須 | IPv4文字列         |
| `url_path`             | string | 必須 | 完全URLパス         |
| `access_timestamp_utc` | string | 必須 | RFC 3339形式UTC日時 |
| `referer`              | string | 必須 | 欠損時は空文字         |
| `user_agent`           | string | 必須 | 欠損時は空文字         |

## 21.4 正常レスポンス

ルールに一致した場合：

```json
{
  "access_id": "access-000001",
  "selected_policy_id": "policy-100",
  "matched_rule_id": "rule-200",
  "action": "DENY"
}
```

デフォルトアクションの場合：

```json
{
  "access_id": "access-000002",
  "selected_policy_id": "policy-100",
  "matched_rule_id": null,
  "action": "ALLOW"
}
```

## 21.5 HTTP条件

```text
HTTP/1.1
JSON
Keep-Alive使用
同時実行数1
HTTPパイプライニングなし
```

TCP接続は計測前に確立してよい。

DB接続プールも計測前に初期化してよい。

各リクエストは、前のレスポンス本文を完全に受信した後に送信する。

## 21.6 レイテンシ計測範囲

開始：

```text
リクエスト本文の送信開始
```

終了：

```text
レスポンス本文の受信完了
```

以下は不正解として扱う。

* HTTP 200以外
* タイムアウト
* JSON解析エラー
* 必須項目欠落
* access_id不一致
* 不正なaction
* 不正なmatched_rule_id
* 不正なselected_policy_id
* 接続切断
* 複数レスポンス

---

# 22. バッチ部門インターフェース

参加システムは以下の実行可能ファイルまたはスクリプトを提供する。

```text
/benchmark/run-batch
```

実行例：

```bash
/benchmark/run-batch \
  --input /benchmark/input/access_logs.parquet \
  --output /benchmark/output/results.csv
```

内部実装は自由とする。

* DBへのアクセスログ投入
* 一時テーブル作成
* 一時インデックス作成
* Parquet直接読み込み
* オンメモリ処理
* 複数プロセス並列
* DB内SQL処理
* 独自マッチングエンジン

## 22.1 計測開始

```text
run-batchプロセスの開始時点
```

## 22.2 計測終了

以下をすべて満たした時点。

* 全アクセスログの処理が完了
* `results.csv`の書き込みが完了
* ファイル内容がflush済み
* `run-batch`が終了コード0で終了

プロセス終了後にバックグラウンドでCSVを書き続けてはならない。

---

# 23. バッチ出力CSV

出力ファイル：

```text
results.csv
```

レイアウト：

```csv
access_id,selected_policy_id,matched_rule_id,action
access-000001,policy-100,rule-200,DENY
access-000002,policy-100,,ALLOW
access-000003,policy-301,rule-999,ALLOW
```

## 23.1 CSV仕様

* 文字コード：UTF-8
* ヘッダー：必須
* 区切り文字：カンマ
* 改行：LF
* クォート：RFC 4180相当
* 余分な列は禁止
* `action`は大文字の`ALLOW`または`DENY`
* `matched_rule_id`がNULLの場合は空フィールド
* `NULL`または`null`という文字列は使用しない
* 同一`access_id`の重複は禁止
* 入力に存在しない`access_id`の出力は禁止

## 23.2 出力順序

出力行の順序は自由とする。

入力アクセスログと同じ順序である必要はない。

採点側は`access_id`で正解データと突合する。

出力順序を自由にすることで、並列処理後の並べ替えコストを必須にしない。

---

# 24. バッチ結果検証

採点側は以下を確認する。

1. CSVを正常に解析できる
2. ヘッダーが定義と完全一致する
3. 出力件数が入力件数と一致する
4. access_idの重複がない
5. 未知のaccess_idが存在しない
6. 全access_idが出力されている
7. selected_policy_idが正解と一致する
8. matched_rule_idが正解と一致する
9. actionが正解と一致する

すべて一致した場合のみ性能順位の対象とする。

---

# 25. Docker実行条件

すべてローカルPCのDocker上で実行可能であること。

単一コンテナに限定しない。

以下を許可する。

* アプリケーションコンテナ
* DBコンテナ
* キャッシュコンテナ
* 検索エンジンコンテナ
* 補助プロセス
* Docker Compose構成

外部クラウドや別PCへ処理を委譲してはならない。

インターネット接続を必要としないこと。

正式なCPU数、メモリ上限、ディスク上限は別途ベンチマーク環境仕様として定義する。

---

# 26. 禁止事項

以下を禁止する。

* 外部クラウドへの処理委譲
* 別PCへの処理委譲
* 実行時インターネットアクセス
* 正解データをマッチング入力に使用する
* access_idから正解を検索する
* 採点対象アクセスログの事前読み込み
* 採点対象結果の事前計算
* 固定レスポンス
* 入力に応じた正しい評価をせず結果を返す
* リソース制限外のホストプロセス利用
* CSV書き込み完了前の正常終了
* READY状態になる前に計測開始を要求する

---

# 27. 参照実装

正解データ生成用の参照実装は、性能より正確性を優先する。

処理手順：

```text
1. URLパスからtenant_idとdepartment_idを取得
2. 部署ポリシーの存在を確認
3. 部署ポリシーが存在すれば部署ポリシーを選択
4. 存在しなければテナントポリシーを選択
5. 選択ポリシーのルールをpriority昇順で評価
6. 最初に一致したルールを返す
7. 一致ルールがなければdefault_actionを返す
```

IPグループルールの場合：

```text
1. 対象部署にIPグループ設定が存在するか確認
2. 存在すれば部署グループセットを選択
3. 存在しなければテナントグループセットを選択
4. ルールのgroup_keyに対応するグループを検索
5. 存在しなければルール不一致
6. source_ipv4がグループメンバーに完全一致すればルール一致
```

---

# 28. 未確定事項

以下は後続設計で決定する。

* 入力データの正式なファイル形式
* CSVとParquetの両方を提供するか
* 正式なテーブル物理レイアウト
* IDの最大文字数
* tenant_id、department_idの文字種
* URLパスの最大長
* Refererの最大長
* User-Agentの最大長
* 正規表現パターンの最大長
* HTTP APIのタイムアウト
* バッチ処理のタイムアウト
* DockerのCPU上限
* Dockerのメモリ上限
* 一時ディスク上限
* バッチアクセスログ件数
* 逐次アクセスログ件数
* ウォームアップ回数
* 本計測回数
* 順位に中央値を使うか最小値を使うか
* リソース使用量を順位に含めるか参考値とするか
* ルールやIPグループ更新の適合試験内容
* ポリシーが存在しないアクセスをデータに含めるか
* URLパスの形式不正データを含めるか
* enabled=falseの扱いをデータセットへ含めるか
