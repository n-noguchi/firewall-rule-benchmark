# PostgreSQL Indexed 実装

`priority-indexed-implementation` と同じ優先度索引エンジンを踏襲しつつ、ユーザー管理マスタの正本を独立した PostgreSQL 17 コンテナへ永続化した参加実装です。マスタDBプロセスと評価プロセスを別OSプロセス・別コンテナに分離しているため、ルール 1.1.1（DBコンテナとアプリケーションコンテナの分離）を満たします。

## 設計

| 要素 | 選択 |
| --- | --- |
| マスタDB | PostgreSQL 17 (`postgres:17-alpine`)、別コンテナ・別プロセス |
| アプリケーション | Go 1.25、Pure-Go の `github.com/jackc/pgx/v5` で接続 |
| 評価エンジン | `priority-indexed-implementation` と同じ条件別最小 priority 索引（オンメモリ） |
| バッチ | サーバー内で評価済みのスナップショットを使い、ファイルへ結果を書くだけ |

PostgreSQL 上の物理スキーマは `policies` / `firewall_rules` / `source_ip_groups` / `source_ip_group_members` / `metadata` の5テーブルです。提供CSVは空データベースへの初回投入だけに使い、以後の起動と更新反映ではDBからマスタデータを取得します。

起動時のフロー:

1. `pgxpool` で PostgreSQL へ接続し、必要ならスキーマを作成
2. `metadata.initialized` を確認し、未初期化なら提供CSVを `COPY` で一括投入
3. 全件 `SELECT` して評価エンジンを構築（regex コンパイル、時刻索引、IPグループ逆引き）
4. `/health/ready` が `READY` を返したら計測可能

計測区間では PostgreSQL への問い合わせ、索引の再構築、正規表現の再コンパイルを行いません。`/benchmark/run-batch` は起動済みサーバーへファイルパスを渡す HTTP ラッパーです。

### マスタ更新

文書化した管理 CRUD API から、マスタ1件単位の参照・作成・置換・削除ができます。

| マスタ | GET / PUT / DELETE |
| --- | --- |
| ポリシー | `/v1/admin/policies/{policy_id}` |
| ファイアウォールルール | `/v1/admin/rules/{rule_id}` |
| IPグループ | `/v1/admin/source-ip-groups/{group_id}` |
| IPv4メンバー | `/v1/admin/source-ip-groups/{group_id}/members/{source_ipv4}` |

`PUT` はURLのIDを識別子とする1件の完全置換（UPSERT）です。更新は単一トランザクションへ書き込み、**コミット前に同一トランザクションでエンジンを再構築して検証**し、問題があればロールバックします。整合性が保たれた場合のみコミットし、成功後に `atomic.Pointer` で評価用スナップショットを切り替えます。応答が返るまでは既存スナップショットで評価を継続します。制約違反はHTTP 409、存在しないキーへのアクセスは404を返します。成功後の `data_version` は `release-v1+r1` のようにリビジョンを含みます。

## ローカルで正しさを確認

```powershell
cd postgres-indexed-implementation
go test ./...
go run . validate `
  --data ../benchmark-data/development-v1 `
  --input ../benchmark-data/development-v1/access_logs `
  --expected ../benchmark-data/development-v1/expected_results
```

PostgreSQL 接続を含む `admin_test.go` は `MASTER_DATABASE_URL` が未設定だとスキップされます。実行する場合は、`docker compose up -d db` で PostgreSQL を起動し、ホスト公開ポート（既定 5433）を指定します。

```powershell
docker compose up -d db
$env:MASTER_DATABASE_URL = "postgres://firewall:firewall@127.0.0.1:5433/firewall?sslmode=disable"
go test -run TestMasterDBImportCRUDAndPersistence ./...
```

## Docker で起動

```powershell
cd postgres-indexed-implementation
$env:BENCHMARK_DATASET_PATH = '../benchmark-data/release-v1'
$env:BENCHMARK_HOST_PORT = '18080'   # 8080 が空いていれば省略可能
docker compose up --build -d
docker compose ps
curl.exe http://127.0.0.1:18080/health/ready

docker compose exec target /benchmark/run-batch `
  --input /benchmark/input/access_logs `
  --output /benchmark/output/results.csv

docker compose down
```

逐次APIは仕様どおり `POST /v1/firewall/evaluate`、READY確認は `GET /health/ready` です。コンテナ実行中のバッチ出力は [output](output)、PostgreSQL のデータファイルは [state/pg](state) に作られます。同じデータセットで再起動した場合はCSVを再投入せず、既存DBを読みます。別データセットへ切り替える場合は、保守済みデータを誤って上書きしないよう `state/pg` を退避してから起動してください。

## 再起動後の計測

正式計測は [MEASUREMENT.md](../priority-indexed-implementation/MEASUREMENT.md) と同じ手順で、データセットのmanifest、Gitコミット、Docker制限を固定して実施します。`release-v1` では、初回の COPY 投入と索引構築を含む READY まで約3分、PostgreSQL データサイズは 2.1 GiB でした。

```powershell
docker compose exec target /benchmark/firewall-postgres-indexed benchmark `
  --mode batch `
  --input /benchmark/input/access_logs `
  --expected /benchmark/expected_results `
  --output-directory /benchmark/output `
  --warmup 3 --runs 7 `
  --report /benchmark/output/release-batch-report.json
```

## release-v1 計測結果

計測環境: Windows + Docker Desktop、8 CPU / 16 GiB、dataset manifest SHA-256 `F68E26416CC8429720E96D10A5B5AC1D7372819A1245A7E62B54FBDDBC87764D`、Dockerイメージ `e92999a4ec97`、Git commit `92fc2b4`。

### バッチ部門（主スコア）

| run | elapsed | correct |
| --: | ------: | :-----: |
| 1 | 173.913 ms | ✓ |
| 2 | 239.894 ms | ✓ |
| 3 | 156.218 ms | ✓ |
| 4 | 144.468 ms | ✓ |
| 5 | 150.726 ms | ✓ |
| 6 | 139.631 ms | ✓ |
| 7 | 137.981 ms | ✓ |

**中央値 150.726 ms / 25,000件**（約 165,866 件/秒）。全件正解。

### 逐次部門

| run | total | p50 | p99 | max | correct |
| --: | ----: | --: | --: | --: | :-----: |
| 1 | 18.737 s | 545.9 µs | 1.172 ms | 10.831 ms | ✓ |
| 2 | 18.573 s | 551.4 µs | 1.133 ms | 2.779 ms | ✓ |
| 3 | 18.598 s | 551.1 µs | 1.137 ms | 2.262 ms | ✓ |
| 4 | 18.548 s | 548.4 µs | 1.143 ms | 2.594 ms | ✓ |
| 5 | 18.576 s | 551.9 µs | 1.131 ms | 4.347 ms | ✓ |

**中央値（全件処理時間） 18.576 s / 25,000件**、p50 551 µs、p99 1.14 ms。全件正解。

参考値として、`priority-indexed-implementation`（bbolt 同一プロセス・同一コンテナ構成で順位対象外）は同じ `release-v1` でバッチ 135.8 ms、逐次 15.88 s を記録しています。本実装はPostgreSQLコンテナを分離しつつ、バッチで +11%、逐次で +17% の範囲に収まっています。
