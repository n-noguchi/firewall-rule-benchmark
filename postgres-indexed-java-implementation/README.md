# PostgreSQL Indexed Java 実装

`postgres-indexed-implementation`（Go 版）と同じ優先度索引評価エンジンを Java 21 で再実装し、マスタDB を独立した PostgreSQL 17 コンテナへ分離した参加実装です。ルール 1.1.1（DB コンテナとアプリケーションコンテナの分離）を満たします。

## 設計

| 要素 | 選択 |
| --- | --- |
| マスタDB | PostgreSQL 17 (`postgres:17-alpine`)、別コンテナ・別プロセス |
| アプリケーション | Java 21 (LTS)、Maven ビルド、JDK 標準 `com.sun.net.httpserver.HttpServer` |
| DB 接続 | PostgreSQL JDBC 42.7 + HikariCP 5.1（`reWriteBatchedInserts=true` で高速一括投入） |
| JSON | Jackson 2.17 |
| Parquet 読み取り | `parquet-avro` 1.13 + `hadoop-common`/`hadoop-mapreduce-client-core` 3.3.6 |
| 評価エンジン | `priority-indexed-implementation` と同じ条件別最小 priority 索引を Java 移植 |

PostgreSQL 上の物理スキーマは `policies` / `firewall_rules` / `source_ip_groups` / `source_ip_group_members` / `metadata` の5テーブルです。提供CSVは空データベースへの初回投入だけに使い、以後の起動と更新反映ではDBからマスタデータを取得します。

起動時のフロー:

1. `waitForMaster` で PostgreSQL の応答を待機
2. HikariCP プールを開き、必要ならスキーマを作成
3. `metadata.initialized` を確認し、未初期化なら JDBC バッチ INSERT で提供CSVを一括投入
4. 全件 `SELECT` して評価エンジンを構築（regex コンパイル、時刻索引、IP グループ逆引き）
5. `/health/ready` が `READY` を返したら計測可能

計測区間では PostgreSQL への問い合わせ、索引の再構築、正規表現の再コンパイルを行いません。`/benchmark/run-batch` は起動済みサーバーへファイルパスを渡す HTTP ラッパーです。

### マスタ更新

文書化した管理 CRUD API から、マスタ1件単位の参照・作成・置換・削除ができます。

| マスタ | GET / PUT / DELETE |
| --- | --- |
| ポリシー | `/v1/admin/policies/{policy_id}` |
| ファイアウォールルール | `/v1/admin/rules/{rule_id}` |
| IPグループ | `/v1/admin/source-ip-groups/{group_id}` |
| IPv4メンバー | `/v1/admin/source-ip-groups/{group_id}/members/{source_ipv4}` |

`PUT` は UPSERT、`DELETE` は1件削除です。更新は単一 JDBC トランザクションで行い、**コミット前に同一トランザクションでエンジンを再構築して検証**します。検証が通ればコミットし、`EngineHolder` の `AtomicReference` を介して評価用スナップショットをアトミックに切り替えます。検証失敗時はトランザクションをロールバックし、HTTP 409 を返します。成功後の `data_version` は `release-v1+r1` のようにリビジョンを含みます。

## ローカルで正しさを確認

Maven が無い場合は Docker の `maven:3.9-eclipse-temurin-21` イメージ経由でビルドします。次の例は Docker Compose で PostgreSQL を起動した後、Java プロセスをホスト側で動かす代わりにコンテナ内で `validate` します。

```powershell
cd postgres-indexed-java-implementation
docker compose up -d db
docker compose run --rm target validate `
  --data /benchmark/data `
  --input /benchmark/input/access_logs `
  --expected /benchmark/expected_results
```

## Docker で起動

```powershell
cd postgres-indexed-java-implementation
$env:BENCHMARK_DATASET_PATH = '../benchmark-data/release-v1'
$env:BENCHMARK_HOST_PORT = '18082'   # 8082 が空いていれば省略可能
docker compose up --build -d
docker compose ps
curl.exe http://127.0.0.1:18082/health/ready

docker compose exec target /benchmark/run-batch `
  --input /benchmark/input/access_logs `
  --output /benchmark/output/results.csv

docker compose down
```

逐次APIは仕様どおり `POST /v1/firewall/evaluate`、READY確認は `GET /health/ready` です。コンテナ実行中のバッチ出力は [output](output)、PostgreSQL のデータファイルは [state/pg](state) に作られます。同じデータセットで再起動した場合はCSVを再投入せず、既存DBを読みます。別データセットへ切り替える場合は、保守済みデータを誤って上書きしないよう `state/pg` を退避してから起動してください。

## 再起動後の計測

`release-v1` では、初回の JDBC バッチ投入と索引構築を含む READY まで約2.5分、PostgreSQL データサイズは 2.1 GiB でした。バッチ部門はコンテナ内で完結させ、結果ファイルは `/tmp/bench-output` へ出力して計測しています（Docker Desktop on Windows のバインドマウントで `Files.list` が遅延する現象を回避するため、ホスト側 `./output` を使わないのが安全です）。

```powershell
docker compose exec target java -jar /benchmark/firewall-postgres-java.jar benchmark `
  --mode batch `
  --input /benchmark/input/access_logs `
  --expected /benchmark/expected_results `
  --output-directory /tmp/bench-output `
  --warmup 3 --runs 7 `
  --report /tmp/release-batch-report.json
```

## release-v1 計測結果

計測環境: Windows + Docker Desktop、8 CPU / 16 GiB、dataset manifest SHA-256 `F68E26416CC8429720E96D10A5B5AC1D7372819A1245A7E62B54FBDDBC87764D`、Dockerイメージ `3e08e5a6322b`（686 MiB）、Git commit `9c139d7`。JVM 起動オプション `-XX:+UseParallelGC -Xms2g -Xmx12g`。

### バッチ部門（主スコア）

| run | elapsed | correct |
| --: | ------: | :-----: |
| 1 | 104.564 ms | ✓ |
| 2 | 113.493 ms | ✓ |
| 3 | 107.622 ms | ✓ |
| 4 | 105.797 ms | ✓ |
| 5 | 106.791 ms | ✓ |
| 6 |  97.471 ms | ✓ |
| 7 |  97.790 ms | ✓ |

**中央値 105.797 ms / 25,000件**（約 236,300 件/秒）。全件正解。同じ PostgreSQL 構成の Go 版（中央値 150.726 ms）より約 30% 高速で、JIT が効いた Java 評価エンジンの強みが出ています。

### 逐次部門（1 試行）

| run | total | p50 | p99 | max | correct |
| --: | ----: | --: | --: | --: | :-----: |
| 1 | 1111.189 s | 43.968 ms | 48.060 ms | 55.315 ms | ✓ |

**全件処理時間 1111.189 s / 25,000件**、p50 43.97 ms、p99 48.06 ms。全件正解。

逐次部門は Go 版（18.576 s）より大幅に遅いです。プロファイル上、評価エンジン自体はバッチ部門と同等に高速（4 µs/件 程度）ですが、JDK 標準の `com.sun.net.httpserver.HttpServer` と `java.net.http.HttpClient` の組み合わせで HTTP/1.1 Keep-Alive 接続の再利用が期待通り効いておらず、各リクエストのレイテンシの大半が HTTP のオーバーヘッドです。バッチ部門はこの HTTP レイテンシを回避するためオンメモリの一括評価を使い、性能を引き出しています。
