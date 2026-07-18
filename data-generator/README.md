# 提供データ生成器

このディレクトリは、ベンチマークのマスタデータとアクセスログを**決定的**に生成します。`expected_results` は生成しません。正解は必ずトップレベルの `reference-implementation` で評価して作成してください。

## 実行方法

```powershell
python -m pip install -r requirements.txt
python generate.py --profile development --output ../benchmark-data/development-v1
python generate.py --profile release --output ../benchmark-data/release-v1
# 参照実装で expected_results を作成した後
python generate.py --refresh-manifest --output ../benchmark-data/development-v1
python generate.py --verify --output ../benchmark-data/development-v1
```

既存の出力先を上書きするには `--force` を付けます。生成器はシードを使わず、同一バージョンでは常に同じ内容を生成します。`release` は README の上限規模（20,000 テナント、最大規模 20 テナント、各 250,000 ルール）を生成するため、十分なディスク容量と時間を要します。

## プロファイル

| プロファイル | 用途 | テナント数 | 最大規模テナント | 最大規模テナントのルール数 | アクセスログ数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `development` | Git 管理する開発・適合試験用データ | 24 | 2 | 60 | 720 |
| `release` | 正式計測用の生成設定 | 20,000 | 20 | 250,000 | 25,000 |

どちらも 6 種類のルール、部署優先、IP グループの部署優先、日跨ぎ時刻範囲、ルール不一致時のデフォルトアクションを含みます。`enabled=false` は README で扱いが未確定のため、生成しません。

## ファイル分割

CSV はヘッダーを含めて 48 MiB 以下でローテーションします。アクセスログ Parquet は 48 MiB を超えた場合に `access_logs-00000.parquet` のように分割します。生成後の `manifest.json` に各ファイルの SHA-256、行数、サイズを記録します。

詳細な入力・データセット仕様は生成先の `README.md` に書き出されます。
