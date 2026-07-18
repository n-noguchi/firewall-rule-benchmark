# Firewall Rule Benchmark

マルチテナント SaaS 向け L7 ファイアウォールのルール評価性能を比較するためのベンチマークです。アクセスログを、テナント・部署ごとのポリシーと優先順位付き First Match ルールで評価し、結果の正確性と処理性能を測定します。

正式なルール、データモデル、入出力契約、禁止事項は [BENCHMARK_RULES.md](BENCHMARK_RULES.md) を参照してください。この README はリポジトリの案内とスコアボードです。

## ディレクトリ構成

| ディレクトリ／ファイル | 内容 |
| --- | --- |
| [BENCHMARK_RULES.md](BENCHMARK_RULES.md) | ベンチマークのルールと外部インターフェース仕様 |
| [benchmark-data](benchmark-data/README.md) | Git 管理する提供データ。開発用データ、正解データ、manifest を格納 |
| [data-generator](data-generator/README.md) | 決定的なマスタデータ・アクセスログ生成器。`development` と正式計測向け `release` プロファイルを提供 |
| [reference-implementation](reference-implementation/README.md) | 正確性を優先した Go の参照実装。バッチ、READY、逐次 API、正解データ検証を提供 |

## ベンチマーク部門

| 部門 | 実行内容 | 正しさの確認 | 主スコア |
| --- | --- | --- | --- |
| バッチ | 全アクセスログを一括で評価し `results.csv` を出力 | `expected_results` と access_id 単位で完全一致 | プロセス開始から結果 CSV の flush・終了までの経過時間（短いほど良い） |
| 逐次 | 1 リクエストずつ `POST /v1/firewall/evaluate` で評価（同時実行数 1） | 各レスポンスを `expected_results` と完全一致 | 全件処理時間（短いほど良い） |

逐次部門では、主スコアに加えて p50・p99・最大レイテンシを参考値として報告します。いずれの部門も出力の形式・件数・一意な access_id・判定結果がすべて正しいことが採点の前提です。

## 提供データと実行

Git に含まれる [development-v1](benchmark-data/development-v1/README.md) は、実装・適合試験用の 720 アクセスログを含みます。`expected_results` は参照実装で生成済みです。各ファイルは 48 MiB 以下で、`manifest.json` に SHA-256・行数・サイズを記録しています。

```powershell
# 参照実装による開発用データの再評価・正解照合
cd reference-implementation
go run . validate `
  --data ../benchmark-data/development-v1 `
  --input ../benchmark-data/development-v1/access_logs `
  --expected ../benchmark-data/development-v1/expected_results
```

正式計測向けの `release` プロファイルは 20,000 テナント、最大規模 20 テナント、各 250,000 ルールを生成します。生成と正解データ作成の手順は [benchmark-data/README.md](benchmark-data/README.md) にあります。

## スコアボード

現時点では、参照実装は正解データ作成・適合確認用であり、順位用の性能計測は未実施です。参照実装の計測値を参加実装の順位に使うことはありません。

| データセット | 部門 | 正確性 | 主スコア | 参考スコア（p50 / p99 / max） |
| --- | --- | --- | --- | --- |
| development-v1 | バッチ | 720 件の expected_results と一致 | 未計測 | — |
| development-v1 | 逐次 | HTTP API の READY・評価レスポンスを確認済み | 未計測 | 未計測 |
| release-v1 | バッチ | 生成・正解データ作成後に判定 | 未計測 | — |
| release-v1 | 逐次 | 生成・正解データ作成後に判定 | 未計測 | 未計測 |

スコアを掲載する際は、データセットの manifest SHA-256、実装のコミット、実行コマンド、CPU・メモリ制限、各測定値を併記してください。
