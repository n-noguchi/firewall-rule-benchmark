# Firewall Rule Benchmark

マルチテナント SaaS 向け L7 ファイアウォールのルール評価性能を比較するためのベンチマークです。アクセスログを、テナント・部署ごとのポリシーと優先順位付き First Match ルールで評価し、結果の正確性と処理性能を測定します。

正式なルール、データモデル、入出力契約、禁止事項は [BENCHMARK_RULES.md](BENCHMARK_RULES.md) を参照してください。この README はリポジトリの案内とスコアボードです。

参加実装は、ユーザー管理マスタの正本となるマスタDBを持つ必要があります。マスタDBと評価・ルールマッチングを行うアプリケーションは、別のOSプロセスかつ別のDockerコンテナとして構成してください。アプリケーションがDB永続ファイルを直接開く組み込みDB構成は適合しません。提供CSVはマスタDBへの初期投入用であり、CSVから評価用索引を直接構築するだけの構成も適合しません。DBのスキーマや格納形式は自由で、提供データと同じレイアウトで保持する必要はありませんが、マスタメンテナンスから個別データを容易に変更できる必要があります。

## ディレクトリ構成

| ディレクトリ／ファイル | 内容 |
| --- | --- |
| [BENCHMARK_RULES.md](BENCHMARK_RULES.md) | ベンチマークのルールと外部インターフェース仕様 |
| [benchmark-data](benchmark-data/README.md) | Git 管理する共通データ。開発用 `development-v1`、正式計測用 `release-v1`、正解データ、manifest を格納 |
| [data-generator](data-generator/README.md) | 決定的なマスタデータ・アクセスログ生成器。`development` と正式計測向け `release` プロファイルを提供 |
| [reference-implementation](reference-implementation/README.md) | 正確性を優先した Go の参照実装。バッチ、READY、逐次 API、正解データ検証を提供 |
| [priority-indexed-implementation](priority-indexed-implementation/README.md) | 条件別の一致候補から最小 priority を選び First Match を再現する Go 実装。事前ロード型バッチ、逐次 API、Docker 構成を提供 |

## ベンチマーク部門

| 部門 | 実行内容 | 正しさの確認 | 主スコア |
| --- | --- | --- | --- |
| バッチ | 全アクセスログを一括で評価し `results.csv` を出力 | `expected_results` と access_id 単位で完全一致 | プロセス開始から結果 CSV の flush・終了までの経過時間（短いほど良い） |
| 逐次 | 1 リクエストずつ `POST /v1/firewall/evaluate` で評価（同時実行数 1） | 各レスポンスを `expected_results` と完全一致 | 全件処理時間（短いほど良い） |

逐次部門では、主スコアに加えて p50・p99・最大レイテンシを参考値として報告します。いずれの部門も出力の形式・件数・一意な access_id・判定結果がすべて正しいことが採点の前提です。

## 提供データと実行

Git に含まれる [development-v1](benchmark-data/development-v1/README.md) は実装・適合試験用、[release-v1](benchmark-data/release-v1/README.md) はすべての参加実装が使用する正式計測用の固定データセットです。`expected_results` は参照実装で生成済みです。各ファイルは 48 MiB 以下で、データセットごとの `manifest.json` に SHA-256・行数・サイズを記録しています。

```powershell
# 参照実装による開発用データの再評価・正解照合
cd reference-implementation
go run . validate `
  --data ../benchmark-data/development-v1 `
  --input ../benchmark-data/development-v1/access_logs `
  --expected ../benchmark-data/development-v1/expected_results
```

正式計測では、Git管理された共通の `release-v1` を変更せずに使用します。このデータセットは20,000テナント、最大規模20テナント、各250,000ルールを含みます。検証方法と次版を作る場合の手順は [benchmark-data/README.md](benchmark-data/README.md) にあります。

## スコアボード

参照実装は正解データ作成・適合確認用であり、順位用の性能計測には使用しません。主スコアは本計測各runの中央値です。

| 実装 | データセット | システム構成要素 | 使用言語 | 部門 | 正確性 | 主スコア | 参考スコア |
| --- | --- | --- | --- | --- | --- | ---: | --- |
| priority-indexed | release-v1 | Go単一プロセス＋同一コンテナ内bbolt 1.5.0＋オンメモリ優先度索引 | Go 1.25.12 | バッチ | 結果一致、DBプロセス・コンテナ分離要件に未適合 | 参考値 135.845 ms / 25,000件 | 184,034件/秒、順位対象外 |
| priority-indexed | release-v1 | Go単一プロセス＋同一コンテナ内bbolt 1.5.0＋オンメモリ優先度索引 | Go 1.25.12 | 逐次 | 結果一致、DBプロセス・コンテナ分離要件に未適合 | 参考値 15.880 s / 25,000件 | 1,574件/秒、順位対象外 |

スコアを掲載する際は、データセットの manifest SHA-256、実装のコミット、実行コマンド、CPU・メモリ制限、各測定値を併記してください。

旧構成によるスコアの分布、構成、環境、実行方法は [release-v1 性能参考計測](priority-indexed-implementation/RELEASE_BENCHMARK_RESULTS.md) を参照してください。過去の `development-v1` 予備計測は [予備計測結果](priority-indexed-implementation/BENCHMARK_RESULTS.md) に分離しています。

本番条件は8 CPU・16 GiB、manifest SHA-256 `F68E26416CC8429720E96D10A5B5AC1D7372819A1245A7E62B54FBDDBC87764D`、Dockerイメージ `sha256:60e2a60322c52b7e64efac71a6fada64cd020a0c99d7ef7c4da489f93bac19dd` です。
