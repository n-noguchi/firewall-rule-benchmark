# 再起動後の計測手順

## 1. 条件を固定

1. PCを再起動し、計測に不要なアプリケーションを終了する。
2. `git status --short` と `git rev-parse HEAD` を記録する。
3. 対象データセットの `manifest.json` と、そのSHA-256を記録する。
4. Docker Desktopへ割り当てるCPU数・メモリ量を固定し、値を記録する。
5. 電源モード、AC接続、CPU温度が安定していることを確認する。

## 2. READYまで準備

```powershell
cd priority-indexed-implementation
$env:BENCHMARK_DATASET_PATH = '../benchmark-data/release-v1'
$env:BENCHMARK_HOST_PORT = '18080'
docker compose build
docker compose up -d
docker compose ps
curl.exe http://127.0.0.1:18080/health/ready
```

初回計測では `state/master.db` が存在しない状態から起動し、提供CSVがbboltへ変換投入されることを確認します。既存の保守済みDBは削除せず、コンテナ停止後に別名へ退避してください。

`status=READY` と対象の `data_version` を確認してから計測を開始します。イメージビルド、コンテナ起動、CSVからDBへの初期投入、DBからのマスタ取得、索引構築、正規表現コンパイルは計測に含めません。

## 3. バッチ

正式な計測器から、コンテナ内の次のプロセス開始から終了コード0までを測ります。

```powershell
docker compose exec target /benchmark/run-batch `
  --input /benchmark/input/access_logs `
  --output /benchmark/output/results.csv
```

各試行後に、出力CSVを参照実装の `validate` 相当で全件照合します。ウォームアップ回数、本計測回数、試行間の待機時間は全実装で同じにします。

## 4. 逐次

HTTP/1.1 Keep-Aliveの単一接続を使い、同時実行数1で入力を1件ずつ送ります。前レスポンス本文の受信完了後に次を送信し、各リクエストの送信開始から本文受信完了までを記録します。

報告値は全件処理時間を主スコア、p50・p99・最大レイテンシを参考値とします。全レスポンスを `access_id`、`selected_policy_id`、`matched_rule_id`、`action` の4項目で正解照合します。

## 5. 記録項目

```text
dataset manifest SHA-256:
git commit:
Docker image ID:
master DB / version / file size:
CPU limit:
memory limit:
batch elapsed (each run):
sequential total (each run):
sequential p50 / p99 / max:
correctness:
master CRUD / persistence check:
```
