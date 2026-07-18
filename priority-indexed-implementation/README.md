# 優先度索引（Priority Indexed）実装

正しさを維持したまま、最大500万ルールをアクセスごとに先頭から走査しない参加実装です。Go標準の正規表現エンジンを使うため、RE2互換・部分一致という仕様を維持します。

> [!WARNING]
> この実装はbboltとマッチング処理が同一プロセス・同一コンテナで動作するため、追加されたマスタDBのプロセス・コンテナ分離要件には適合しません。記録済みの性能値は参考値です。

## 設計

選択済みポリシーについて条件種別ごとに「一致する最小 priority のルール」を求め、最後に全種別の最小 priority を返します。これは元の First Match と同値です。

| 条件 | 検索用データ | 評価コストの目安 |
| --- | --- | --- |
| アンカー付き完全一致正規表現 | 値でソートした配列 | `O(log n)` |
| アンカー付き接頭辞正規表現 | 接頭辞長＋接頭辞バケット | 入力長に応じた候補だけ |
| 単純なリテラル正規表現 | `strings.Contains` | 候補数に比例 |
| その他のRE2正規表現 | 事前コンパイル済みフォールバック | 一般式の候補数に比例 |
| IPグループ | IPv4 → group_key の逆引き | 所属グループ数に比例 |
| 時刻範囲 | 少数時は優先度順、64種類以上は86,400秒の点索引 | 少数候補または `O(1)` |

同じ条件を持つ複数ルールでは、priority が最小のルール以外は決して First Match にならないため、ロード時に安全に除去します。完全一致索引はHashMapよりメモリ効率のよいソート済み配列にし、最大規模での常駐メモリを抑えています。

ユーザー管理マスタの正本には、同じアプリケーションプロセス・コンテナ内の組み込みKVS [bbolt](https://github.com/etcd-io/bbolt) 1.5.0を使用しています。ポリシー・ルール・グループは型付きJSON、IPv4メンバーはグループIDとIPv4の複合キーで永続化します。提供CSVは空DBへの初回投入だけに使い、以後の起動と更新反映ではDBからデータを取得します。ただし、この配置は現在の分離要件には未適合です。

バッチ評価はCPU数に応じて並列化します。逐次APIと計測用バッチは、READYになるまでにDBから構築済みの同じ読み取り専用スナップショットを共有します。`/benchmark/run-batch` は起動済みサーバーへファイルパスを渡すだけなので、計測区間でマスタDB走査、索引再構築、正規表現再コンパイルをしません。

## ローカルで正しさを確認

```powershell
cd priority-indexed-implementation
go test ./...
go run . validate `
  --data ../benchmark-data/development-v1 `
  --input ../benchmark-data/development-v1/access_logs `
  --expected ../benchmark-data/development-v1/expected_results
```

単独バッチも実行できます。このコマンドはCSV入力を直接調べる診断用であり、正式なDB構成の計測には使用しません。

```powershell
go run . batch `
  --data ../benchmark-data/development-v1 `
  --input ../benchmark-data/development-v1/access_logs `
  --output output/results.csv
```

## Dockerで起動

```powershell
cd priority-indexed-implementation
docker compose up --build -d
docker compose ps
curl.exe http://127.0.0.1:8080/health/ready

docker compose exec target /benchmark/run-batch `
  --input /benchmark/input/access_logs `
  --output /benchmark/output/results.csv

docker compose down
```

逐次APIは仕様どおり `POST /v1/firewall/evaluate`、READY確認は `GET /health/ready` です。コンテナ実行中のバッチ出力は [output](output)、永続マスタDBは [state](state) に作られます。同じデータセットで再起動した場合はCSVを再投入せず、既存DBを読みます。別データセットへ切り替える場合は、保守済みデータを誤って上書きしないよう新しいDBパスを使います。

## マスタ更新

文書化した管理CRUD APIから、マスタ1件単位の参照・作成または置換・削除ができます。

| マスタ | GET / PUT / DELETE |
| --- | --- |
| ポリシー | `/v1/admin/policies/{policy_id}` |
| ファイアウォールルール | `/v1/admin/rules/{rule_id}` |
| IPグループ | `/v1/admin/source-ip-groups/{group_id}` |
| IPv4メンバー | `/v1/admin/source-ip-groups/{group_id}/members/{source_ipv4}` |

`PUT` はURLのIDを識別子とする1件の完全置換です。`created_at` と `updated_at` は省略でき、アプリケーションが設定します。更新はDBトランザクションへ永続化した後、DB全体から新しい検索スナップショットを構築してアトミックに切り替えます。応答が返るまでは既存スナップショットで評価を継続し、成功後のREADYは `release-v1+r1` のようにリビジョンを含む `data_version` を返します。制約違反は更新をロールバックしてHTTP 409にします。

例として、テナントポリシーを作成または更新するリクエストは次のとおりです。

```powershell
$body = @{
  tenant_id = 'tenant-000001'
  department_id = ''
  default_action = 'DENY'
  enabled = $true
} | ConvertTo-Json

Invoke-RestMethod -Method Put `
  -Uri http://127.0.0.1:8080/v1/admin/policies/policy-000001 `
  -ContentType application/json -Body $body
```

自動テストでは初回CSV変換投入、全4種のCRUD、更新後の評価、制約違反時のロールバック、プロセス再起動後の永続性を確認します。`release-v1` ではDBファイルが4,320,145,408 bytesになり、初回投入と索引構築を含むREADYまで約8分でした。更新反映時間は性能スコアに含めません。

## 再起動後の計測

正式計測では [MEASUREMENT.md](MEASUREMENT.md) の順序で、データセットのmanifest、Gitコミット、Docker制限を固定してから実施してください。

`release-v1` の旧構成による性能参考値は [RELEASE_BENCHMARK_RESULTS.md](RELEASE_BENCHMARK_RESULTS.md) に、過去の `development-v1` 予備計測は [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md) にまとめています。
