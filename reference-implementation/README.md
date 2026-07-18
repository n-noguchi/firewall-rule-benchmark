# 参照実装

正確性を優先する Go 実装です。Go の `regexp` パッケージは RE2 構文を実装しているため、README の正規表現要件にそのまま従います。生成器のモジュールやソースコードは参照せず、データセットの公開ファイルだけを入力にします。

## コマンド

```powershell
go test ./...
go run . batch --data ../benchmark-data/development-v1 --input ../benchmark-data/development-v1/access_logs/access_logs-00000.parquet --output results.csv
go run . validate --data ../benchmark-data/development-v1 --input ../benchmark-data/development-v1/access_logs/access_logs-00000.parquet --expected ../benchmark-data/development-v1/expected_results
go run . serve --data ../benchmark-data/development-v1 --listen 127.0.0.1:8080
```

`batch` は Parquet 入力（または診断用の同スキーマ CSV）を読み、UTF-8・BOM なし・LF・RFC 4180 の `results.csv` を出力します。`--input` には単一ファイルのほか、分割済み入力ファイルを格納したディレクトリも指定できます。`validate` は全 expected-results パートと、再評価した結果を順不同で比較します。

HTTP サーバーは `GET /health/ready` と `POST /v1/firewall/evaluate` を提供します。データ読込・検証を完了した後にだけ READY を返します。

## 意図的な制限

* README section 28 で未決定の `enabled=false` は、曖昧な独自解釈を避けるためロード時に拒否します。提供データには含まれません。
* 選択できるポリシーがないアクセス、不正な URL パス、未知のグループ参照はデータ不整合として失敗します。提供データには含まれません。
* この実装は参照用であり、性能順位のための最適化は行いません。
