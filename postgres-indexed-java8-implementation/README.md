# PostgreSQL Indexed Java 8 実装

`postgres-indexed-implementation`（Go 版）と同じ優先度索引評価エンジンを **Java 8** で再実装し、マスタDB を独立した PostgreSQL 17 コンテナへ分離した参加実装です。ルール 1.1.1（DB コンテナとアプリケーションコンテナの分離）を満たします。

## 設計

| 要素 | 選択 |
| --- | --- |
| マスタDB | PostgreSQL 17 (`postgres:17-alpine`)、別コンテナ・別プロセス |
| アプリケーション | **Java 8 LTS（Temurin 1.8.0_492）**、Maven ビルド、JDK 標準 `com.sun.net.httpserver.HttpServer` |
| DB 接続 | PostgreSQL JDBC 42.7 + HikariCP **4.0.3**（Java 8 をサポートする最終ライン） |
| JSON | Jackson 2.15 |
| Parquet 読み取り | `parquet-avro` 1.13 + `hadoop-common`/`hadoop-mapreduce-client-core` 3.3.6 |
| HTTP クライアント | **`java.net.HttpURLConnection`**（Java 8 標準。`java.net.http.HttpClient` は Java 11 以降） |
| 評価エンジン | `priority-indexed-implementation` と同じ条件別最小 priority 索引を Java 8 へ移植 |

Java 21 版からの主な互換修正:

- `record` 型を `final` クラス + コンストラクタ + フィールドへ置換
- switch 式（arrow 構文）を if-else 連鎖へ置換
- テキストブロック `"""..."""` を文字列結合へ置換
- `Map.of` / `List.of` を `HashMap` / `ArrayList` へ置換
- `try (exchange)` を `try { ... } finally { exchange.close(); }` へ置換（`HttpExchange` は Java 8 では `AutoCloseable` ではない）
- `URLDecoder.decode(String, Charset)` を `URLDecoder.decode(String, String)` へ置換
- `java.net.http.HttpClient` を `HttpURLConnection` へ置換

PostgreSQL 上の物理スキーマは `policies` / `firewall_rules` / `source_ip_groups` / `source_ip_group_members` / `metadata` の5テーブルです。提供CSVは空データベースへの初回投入だけに使い、以後の起動と更新反映ではDBからマスタデータを取得します。マスタ更新は単一 JDBC トランザクションで行い、**コミット前に同一トランザクションでエンジンを再構築して検証**します。検証が通ればコミットし、`EngineHolder` の `AtomicReference` を介して評価用スナップショットをアトミックに切り替えます。検証失敗時はトランザクションをロールバックし、HTTP 409 を返します。成功後の `data_version` は `release-v1+r1` のようにリビジョンを含みます。

## ローカルで正しさを確認

Maven がホストに無い場合は、Docker の `maven:3.8-jdk-8` イメージ経由でビルドします。Docker Compose で PostgreSQL を起動した後、コンテナ内で `validate` します。

```powershell
cd postgres-indexed-java8-implementation
docker compose up -d db
docker compose run --rm target validate `
  --data /benchmark/data `
  --input /benchmark/input/access_logs `
  --expected /benchmark/expected_results
```

## Docker で起動

```powershell
cd postgres-indexed-java8-implementation
$env:BENCHMARK_DATASET_PATH = '../benchmark-data/release-v1'
$env:BENCHMARK_HOST_PORT = '18083'   # 8083 が空いていれば省略可能
docker compose up --build -d
docker compose ps
curl.exe http://127.0.0.1:18083/health/ready

docker compose exec target /benchmark/run-batch `
  --input /benchmark/input/access_logs `
  --output /benchmark/output/results.csv

docker compose down
```

逐次APIは仕様どおり `POST /v1/firewall/evaluate`、READY確認は `GET /health/ready` です。コンテナ実行中のバッチ出力は [output](output)、PostgreSQL のデータファイルは [state/pg](state) に作られます。同じデータセットで再起動した場合はCSVを再投入せず、既存DBを読みます。別データセットへ切り替える場合は、保守済みデータを誤って上書きしないよう `state/pg` を退避してから起動してください。

## 再起動後の計測

`release-v1` では、初回の JDBC バッチ投入と索引構築を含む READY まで約4.5分、PostgreSQL データサイズは 2.1 GiB でした。バッチ部門はコンテナ内で完結させ、結果ファイルは `/tmp/bench-output` へ出力して計測しています（Docker Desktop on Windows のバインドマウントで `Files.list` が遅延する現象を回避するため、ホスト側 `./output` を使わないのが安全です）。

```powershell
docker compose exec target java -jar /benchmark/firewall-postgres-java8.jar benchmark `
  --mode batch `
  --input /benchmark/input/access_logs `
  --expected /benchmark/expected_results `
  --output-directory /tmp/bench-output `
  --warmup 3 --runs 7 `
  --report /tmp/release-batch-report.json
```

## release-v1 計測結果

計測環境: Windows + Docker Desktop、8 CPU / 16 GiB、dataset manifest SHA-256 `F68E26416CC8429720E96D10A5B5AC1D7372819A1245A7E62B54FBDDBC87764D`、Dockerイメージ `9dff1ed23d01`（620 MiB）、Git commit `9c139d7`。JVM 起動オプション `-XX:+UseParallelGC -Xms2g -Xmx12g`。

### バッチ部門（主スコア）

| run | elapsed | correct |
| --: | ------: | :-----: |
| 1 | 128.142 ms | ✓ |
| 2 | 137.495 ms | ✓ |
| 3 | 135.368 ms | ✓ |
| 4 | 114.252 ms | ✓ |
| 5 | 126.969 ms | ✓ |
| 6 | 112.680 ms | ✓ |
| 7 | 114.943 ms | ✓ |

**中央値 126.969 ms / 25,000件**（約 196,900 件/秒）。全件正解。同じ PostgreSQL 構成の Go 版（150.726 ms）より約 16% 高速で、Java 21 版（105.797 ms）よりは約 20% 遅いです。Java 8 の JIT は Java 21 ほど積極的でないことと、String 操作やラムダ生成のインライン化が Java 8 では弱いことが主因と見られます。

### 逐次部門（1 試行）

| run | total | p50 | p99 | max | correct |
| --: | ----: | --: | --: | --: | :-----: |
| 1 | 1113.888 s | 43.978 ms | 48.071 ms | 52.091 ms | ✓ |

**全件処理時間 1113.888 s / 25,000件**、p50 43.98 ms、p99 48.07 ms。全件正解。Java 21 版（1111.189 s）とほぼ同じで、HTTP のオーバーヘッドが支配的なのは Java 21 版と同様です。

### Java 21 版との比較

| 部門 | Java 8 | Java 21 | 比 |
| --- | ---: | ---: | ---: |
| バッチ中央値 | 126.969 ms | 105.797 ms | Java 8 は 1.20 倍 |
| 逐次（1試行） | 1113.888 s | 1111.189 s | Java 8 は 1.00 倍 |

バッチでは Java 8 の方が JIT とインライン化の差で 20% 遅くなりますが、逐次では HTTP のオーバーヘッドが大半を占めるため、Java バージョンの差はほぼ現れません。
