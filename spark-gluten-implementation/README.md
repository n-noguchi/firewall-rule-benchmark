# Spark + Gluten 実装

バッチ部門を [Apache Spark 3.5](https://spark.apache.org/) + [Apache Gluten 1.5](https://gluten.apache.org/) (Velox バックエンド) で実装した参加実装です。評価ロジック全体を単一の Spark SQL プランとして記述します。Gluten が有効なときは Velox のベクトル化実行（正規表現は RE2、JOIN・集計は columnar pipeline）に乗り、Gluten 無効時は同じプランを vanilla Spark で実行します。マスタ DB は既存の Java 版と同じ PostgreSQL 17 コンテナを別プロセス・別コンテナで配置し、ルール 1.1.1（DB コンテナとアプリコンテナの分離）を満たします。

> **2026-07 検証時の構成**: 本リポジトリで確認した範囲では、Gluten 1.5.0 prebuilt Velox バンドルを Ubuntu 22.04 + JDK 17 で動かすには Boost 1.84 のソースビルドが別途必要で、かつ `LowCopyFileSegmentJniByteInputStream` が Netty 4.1.96 の `sun.misc.Unsafe` アクセスに依存する箇所で JDK 17 + Temurin 上で実行時エラーになるため、**本計測は `GLUTEN_ENABLED=0`（vanilla Spark）で実施**しています。`GLUTEN_ENABLED=1` にすると Velox バックエンドが有効になりますが、ネイティブライブラリの調整が別途必要です。詳細は後述の「Gluten を有効にする場合」を参照してください。

## 設計

| 要素 | 選択 |
| --- | --- |
| マスタ DB | PostgreSQL 17 (`postgres:17-alpine`)、別コンテナ・別プロセス |
| アプリ | Java 17 (Temurin) + Spark 3.5.5 (`local[*]`)、JDK 標準 `com.sun.net.httpserver.HttpServer` |
| SQL 加速 | Apache Gluten 1.5.0 + Velox バックエンド（`GLUTEN_ENABLED=1` で有効化） |
| DB 接続 | PostgreSQL JDBC 42.7.4（`/opt/spark/jars` 配備）。マスタ取り込みは JDBC バッチ INSERT |
| 評価エンジン | Spark SQL の 1 プラン。`RLIKE` は Gluten 有効時に Velox の RE2 実装へ委譲（ルール 11 準拠） |

PostgreSQL の物理スキーマは `policies` / `firewall_rules` / `source_ip_groups` / `source_ip_group_members` / `metadata` の 5 テーブルで、`postgres-indexed-java-implementation` と完全一致します。提供 CSV は初回起動時のみ JDBC バッチ INSERT で PostgreSQL へ投入し、2 回目以降は `metadata.initialized` フラグで投入をスキップします。

### Spark SQL 評価プラン

評価は [BatchJob.java](src/main/java/firewall/spark/BatchJob.java) の `EVALUATION_SQL` に集約されています。以下の CTE チェーンで First Match を実装します。

```text
access_enriched   : access_logs から tenant_id/department_id と access_time_sec(UTC 秒) を抽出
policy_selected   : 部署優先でポリシーを 1 つ選択（COALESCE）。IP グループスコープも同時に決定
candidates        : regex / time_range / source_ipv4_group の 3 種を UNION ALL で候補化
first_match       : ROW_NUMBER() OVER (PARTITION BY access_id ORDER BY priority) で rn=1 を選出 = First Match
final SELECT      : policy の default_action でフォールバックしつつ 4 カラムを出力
```

Spark が JOIN を broadcast または sort-merge で並列実行し、Gluten が有効な場合は Velox の columnar operator に置き換えます。`SOURCE_IPV4_GROUP` はルール→IP グループ→IP メンバーの 2 段 JOIN でルール 12/13 の「部署グループ優先・キー欠落時の不一致」を表現します。`ACCESS_TIME_RANGE` の日跨ぎは `start_time_sec > end_time_sec` を OR 条件に変換して扱います（ルール 10.4）。

### 起動フロー

1. `waitForMaster` で PostgreSQL の応答を待機（最大 `PT2M`、`DriverManager` でポーリング）
2. `MasterImporter.open` で JDBC 接続を開き、未初期化なら提供 CSV を JDBC バッチ INSERT
3. SparkSession を `local[*]` で起動（`SparkFactory` が `GLUTEN_ENABLED` に応じて Gluten プラグイン設定を切替）
4. `MasterLoader.loadAndCache` で Spark JDBC 経由で 4 マスタ表を読み込み、`access_time_sec` などの派生列を追加して `.cache()` で列指向に実体化
5. `/health/ready` が `READY` を返したら計測可能

計測区間では PostgreSQL への問い合わせ・索引再構築は発生しません。`/benchmark/run-batch` は起動済みの HTTP サーバーへファイルパスを渡すラッパで、`BatchJob` が `access_logs/*.parquet` を読み、Spark SQL を実行し、`coalesce(1)` で単一 CSV にして `results.csv` へ移動します。

### スコープ外

- 逐次部門 (`POST /v1/firewall/evaluate`) は 501 を返します。本実装はバッチ部門を Spark パターンとして提示するのが目的です。
- マスタメンテナンス CRUD API (`/v1/admin/*`) は未実装です。PostgreSQL へ直接 SQL を発行すれば個別変更可能です（ルール 15 の SQL による操作を満たします）。

## ローカルでビルド

Maven コンテナ経由でビルドします（ホストに Maven 不要）。

```powershell
cd spark-gluten-implementation
docker run --rm `
  -v "${PWD}:/src" `
  -v "${PWD}/.m2:/root/.m2" `
  -w /src maven:3.9-eclipse-temurin-17 mvn -B -DskipTests package
```

## Docker で起動

```powershell
cd spark-gluten-implementation
$env:BENCHMARK_DATASET_PATH = '../benchmark-data/development-v1'
$env:BENCHMARK_HOST_PORT = '18083'   # 8083 が空いていれば省略可能
$env:GLUTEN_ENABLED = '0'            # 1 で Gluten/Velox を有効化（ネイティブ lib 調整が必要）
docker compose up --build -d
docker compose ps
curl.exe http://127.0.0.1:18083/health/ready
```

READY が返ったらバッチを実行します。コンテナ内の `/tmp` 配下へ結果を出力してください（Docker Desktop on Windows のバインドマウントで `Files.list` が遅延する現象を避けるため、ホスト側 `./output` よりコンテナ内 `/tmp` が安全です）。

```powershell
docker compose exec target /benchmark/run-batch `
  --input /benchmark/input/access_logs `
  --output /tmp/results.csv

docker compose exec target sh -c `
  "sort /tmp/results.csv -o /tmp/got.sorted && cat /benchmark/expected_results/*.csv | sort > /tmp/want.sorted && diff /tmp/got.sorted /tmp/want.sorted && echo OK"
```

`development-v1` では `OK` が出れば 720 件すべて正解データと一致しています。

## 計測

正式計測時は `release-v1` をマウントします。`expected_results` は計測の入力・キャッシュに使わないでください（ルール 26）。

```powershell
$env:BENCHMARK_DATASET_PATH = '../benchmark-data/release-v1'
$env:GLUTEN_ENABLED = '0'
docker compose up --build -d
curl.exe http://127.0.0.1:18083/health/ready

# ウォームアップ 1 回 + 本計測 7 回。run-batch は起動済みの SparkSession を再利用するため、
# 2 回目以降は JVM/JIT やアクセスログ読み取りのウォームアップ効果が乗ります。
for ($i = 1; $i -le 7; $i++) {
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  docker compose exec target /benchmark/run-batch `
    --input /benchmark/input/access_logs --output /tmp/results-$i.csv | Out-Null
  $sw.Stop()
  Write-Host ("run {0}: {1} ms" -f $i, $sw.ElapsedMilliseconds)
}
```

主スコアは `run-batch` プロセスの開始から `results.csv` の flush・終了までの経過時間（中央値）です。

## release-v1 計測結果

計測環境: Windows + Docker Desktop、8 CPU / 16 GiB、dataset manifest SHA-256 `F68E26416CC8429720E96D10A5B5AC1D7372819A1245A7E62B54FBDDBC87764D`、`GLUTEN_ENABLED=0`（vanilla Spark 3.5.5）。JVM 起動オプション `-XX:+UseParallelGC -Xms2g -Xmx10g`、`spark.sql.shuffle.partitions=8`。

### バッチ部門（主スコア）

| run | elapsed | correct |
| --: | ------: | :-----: |
| 1 | 7,457 ms | ✓ |
| 2 | 5,346 ms | ✓ |
| 3 | 4,708 ms | ✓ |
| 4 | 4,701 ms | ✓ |
| 5 | 5,018 ms | ✓ |
| 6 | 4,830 ms | ✓ |
| 7 | 4,556 ms | ✓ |

**中央値 5,018 ms / 25,000件**（約 4,982 件/秒）。全件正解。

`run-batch` の経過時間には、HTTP リクエスト / ファイルパス解析 / Parquet 読み取り / Spark SQL 実行 / CSV 出力 + flush が含まれます（ルール 4 準拠）。Spark の JVM 起動や SparkSession 初期化は READY 前に済んでいるため計測外（ルール 3）。

### 既存実装との比較

| 実装 | バッチ中央値 | 件/秒 | 備考 |
| --- | ---: | ---: | --- |
| postgres-indexed-java (Java 21 + オンメモリ索引) | 105.797 ms | 236,300 | 既存・最速 |
| **spark-gluten (Spark 3.5.5)** | **5,018 ms** | **4,982** | 本実装。vanilla Spark 構成 |

Java 版オンメモリ実装と比べると約 50 倍遅いです。25,000 件規模では Spark のタスクスケジューリング・SQL 最適化・columnar 変換の固定オーバーヘッドが支配的で、データ規模に対するメリットが出にくいことを示しています。ルール 14 が想定する最大規模（最大テナント 20 × 250,000 ルール）のように評価対象が巨大になったとき、Spark のパーティション分割・ネイティブ実行が効いてくる余地があります。

## Gluten を有効にする場合

`GLUTEN_ENABLED=1` で起動すると、`SparkFactory` が `spark.plugins=org.apache.gluten.GlutenPlugin` と Velox バックエンド関連の設定を追加します。本リポジトリの Dockerfile はあらかじめ以下を行っています。

- Apache Gluten 1.5.0 binary tarball (`apache-gluten-1.5.0-incubating-bin-spark-3.5.tar.gz`) をダウンロードして `gluten-velox-bundle-spark3.5_2.12-linux_amd64-1.5.0.jar` を `$SPARK_HOME/jars` へ配備
- 同 JAR から `libvelox.so` / `libgluten.so` を `/usr/local/lib/gluten` へ抽出し `ldconfig` 登録
- Boost 1.84.0 をソースからビルドして `libboost_context.so.1.84.0` / `libboost_regex.so.1.84.0` 等を `/usr/local/lib` へ配備
- `spark.gluten.loadLibFromJar=false`（Gluten が JAR 内リソースとして全 `.so` を要求するのを回避）

それでも Velox の `LowCopyFileSegmentJniByteInputStream` が Netty 4.1.96 の `PlatformDependent.directBuffer(addr, size)` を呼び出す経路で `sun.misc.Unsafe` アクセスに失敗するため、現状は vanilla Spark に切り替えています。追加で試す価値がある対策:

- Netty を 4.1.100+ に差し替えて `--add-opens java.base/sun.misc=ALL-UNNAMED` を Spark の executor JVM へ確実に渡す（`spark.driver.extraJavaOptions` 経由等）
- `LowCopyFileSegmentJniByteInputStream.isSupported` が `false` を返すように Spark 側のストリーム wrapping を調整
- Gluten をソースからビルドして `LowCopyFileSegment` の使用を無効化

## 制約・注意

- **Spark の起動コスト**: `release-v1` は 25,000 件と小規模なため、Spark タスクスケジューリングの固定オーバーヘッドが性能比を圧迫します。本実装の価値は、ルール 14 が想定する最大規模（最大テナント 20 × 250,000 ルール = 最大約 500 万ルール）でのスケール時の並列処理が Spark SQL で表現できる点にあります。
- **正規表現の一致**: vanilla Spark 構成では `RLIKE` に `java.util.regex` が使われます。データセットの正規表現は RE2 互換に制限されているため結果は同じになります（ルール 11）。Gluten 有効時は Velox の RE2 実装に切り替わります。
- **出力ファイル名**: Spark の `df.coalesce(1).write.csv()` はディレクトリ配下に `part-*.csv` を生成するため、`BatchJob` が生成後に `results.csv` へリネームしています。ファイルの最終変更時刻はリネーム時点になります。
