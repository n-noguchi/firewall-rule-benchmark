# 提供データ

`development-v1` と `release-v1` は、すべての参加実装が共通利用する Git 管理の固定データセットです。`development-v1` は開発・適合試験用、`release-v1` は正式計測用です。`expected_results` は、生成器ではなくトップレベルの `reference-implementation` で評価して生成済みです。

正式ベンチマークは、リポジトリに含まれる `benchmark-data/release-v1` を変更せずに使用してください。データセット全体は `release-v1/manifest.json` で固定し、manifest自体のSHA-256は `F68E26416CC8429720E96D10A5B5AC1D7372819A1245A7E62B54FBDDBC87764D` です。参加実装ごとに再生成したデータは正式スコアに使用できません。

`policies`、`firewall_rules`、`source_ip_groups`、`source_ip_group_members`のCSVは、参加システムのマスタDBへ初期投入するための提供データです。参加システム固有のスキーマへ変換して投入してよく、同じテーブル・カラム構成で保持する必要はありません。CSVをマスタデータの正本として評価用索引へ直接ロードするだけの構成は、ベンチマークの必須システム構成を満たしません。

`release-v1` を次の版へ更新する場合だけ、同じ決定的生成器と参照実装で次のように再生成します。既存の `release-v1` を再生成して上書きせず、新しいdataset_versionとディレクトリ名を割り当ててレビューしてください。

```powershell
cd data-generator
python generate.py --profile release --output ../benchmark-data/release-v1

cd ../reference-implementation
go run . batch --data ../benchmark-data/release-v1 --input ../benchmark-data/release-v1/access_logs/access_logs-00000.parquet --output ../benchmark-data/release-v1/expected_results/expected_results-00000.csv

cd ../data-generator
python generate.py --refresh-manifest --output ../benchmark-data/release-v1
```

すべての CSV / Parquet / expected-results ファイルは、生成器が 48 MiB 以下に分割します。各ファイルの SHA-256、行数、サイズはデータセットごとの `manifest.json` で検証できます。

```powershell
python data-generator/generate.py --verify --output benchmark-data/release-v1
```
