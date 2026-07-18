# 提供データ

`development-v1` は Git 管理する開発・適合試験用データセットです。`expected_results` は、生成器ではなくトップレベルの `reference-implementation` で評価して生成済みです。

正式計測向けの規模データは、同じ決定的生成器で次のように生成します。上限規模では約 500 万ルールを出力するため、リリース運用で生成・レビューしてからこのディレクトリへ配置してください。

```powershell
cd data-generator
python generate.py --profile release --output ../benchmark-data/release-v1

cd ../reference-implementation
go run . batch --data ../benchmark-data/release-v1 --input ../benchmark-data/release-v1/access_logs/access_logs-00000.parquet --output ../benchmark-data/release-v1/expected_results/expected_results-00000.csv

cd ../data-generator
python generate.py --refresh-manifest --output ../benchmark-data/release-v1
```

すべての CSV / Parquet / expected-results ファイルは、生成器が 48 MiB 以下に分割します。各ファイルの SHA-256、行数、サイズはデータセットごとの `manifest.json` で検証できます。
