# ベンチマーク結果

## 結論

`priority-indexed-implementation` を `development-v1` の720アクセスで予備計測した。全ウォームアップ・全本計測で期待結果と完全一致し、正解率は100%だった。

代表値は各本計測の中央値とする。

| 部門 | 本計測 | 主スコア中央値 | スループット換算 | 参考レイテンシ |
| --- | ---: | ---: | ---: | --- |
| バッチ | 30回 | **24.085 ms / 720件** | **29,894件/秒** | 最小20.839 ms、p95 37.948 ms、最大52.067 ms |
| 逐次 | 30回（2系列合算） | **542.106 ms / 720件** | **1,328件/秒** | run別p50中央値 554.75 µs、run別p99中央値 1,227.35 µs、run別最大値中央値 2,208.45 µs |

今回は実装後にPCを再起動していないため、正式スコアではなく現セッションの予備値である。Windowsの起動時刻は2026-07-18 12:37:38 JST、計測開始は同日21:42 JSTだった。

## 正確性

| 対象 | ウォームアップ | 本計測 | 照合件数 | 結果 |
| --- | ---: | ---: | ---: | --- |
| バッチ | 5回 | 30回 | 25,200件 | 全件一致 |
| 逐次・系列1 | 3回 | 10回 | 9,360件 | 全件一致 |
| 逐次・確認系列 | 3回 | 20回 | 16,560件 | 全件一致 |
| 合計 | 11回 | 60回 | 51,120件 | **不一致0件** |

照合項目は `access_id`、`selected_policy_id`、`matched_rule_id`、`action` の4項目である。

## バッチ分布

ウォームアップ5回後に30回計測した。`/benchmark/run-batch` のプロセス生成直前から、結果CSVをflushして終了コード0で終了するまでをコンテナ内の計測ドライバで測った。正解照合時間は計測値に含めていない。

| 統計 | 720件の処理時間 |
| --- | ---: |
| 最小 | 20.839 ms |
| 中央値 | **24.085 ms** |
| 平均 | 25.612 ms |
| p95 | 37.948 ms |
| 最大 | 52.067 ms |
| 標準偏差（母標準偏差） | 5.814 ms |

大半は21–28 msだったが、37.948 msと52.067 msの外れ値があった。データセットが小さいため、プロセス生成、HTTPによるバッチ指示、Parquet読込、CSV出力の固定費が相対的に大きい。

## 逐次分布

HTTP/1.1 Keep-Alive、同時実行数1、HTTPパイプライニングなしで計測した。TCP接続はREADY取得で事前確立した。各レイテンシはリクエスト送信開始直前からレスポンス本文の受信完了までで、JSON正解照合は停止後に行った。全件時間は720件のリクエスト窓の合計である。

| 統計 | 全件時間 | run別p50 | run別p99 | run別最大値 |
| --- | ---: | ---: | ---: | ---: |
| 最小 | 518.668 ms | 545.40 µs | 1,104.00 µs | 1,230.80 µs |
| 中央値 | **542.106 ms** | **554.75 µs** | **1,227.35 µs** | **2,208.45 µs** |
| 平均 | 596.089 ms | 726.01 µs | 1,574.67 µs | 3,113.83 µs |
| p95 | 813.296 ms | 1,096.50 µs | 3,662.50 µs | 8,008.60 µs |
| 最大 | 888.907 ms | 1,098.40 µs | 3,889.20 µs | 21,351.70 µs |

run別p50が約0.55 msの状態と約1.07 msの状態に分かれる二峰性が確認された。系列1では本計測4本目から高速側へ、確認系列では8–14本目に低速側へ遷移している。Go実装にはJITがないため、対象ロジックのウォームアップというより、Windows/Dockerのネットワーク経路、電源管理、または同時稼働プロセスの影響と考えられる。ただし原因はこの計測だけでは確定できない。

## 計測環境

| 項目 | 値 |
| --- | --- |
| 計測日時 | 2026-07-18 21:42–21:43 JST |
| OS | Windows 11 Pro 10.0.26200 |
| CPU | AMD Ryzen 7 5700X、8コア / 16スレッド |
| 物理メモリ | 51,447,345,152 bytes（約47.91 GiB） |
| 電源プラン | バランス |
| Go | ターゲット go1.25.12 linux/amd64、逐次クライアント go1.25.2 windows/amd64 |
| Docker | Client 28.3.3-rd / Server 29.6.1 |
| Dockerエンジン割当 | 16 CPU、25,153,433,600 bytes（約23.43 GiB） |
| Composeサービス制限 | CPU・メモリとも明示制限なし |
| DockerイメージID | `sha256:4dd72bf3ca8741d1dc926e8f0317fcf0ef01e1442a38783977dd11de455378d9` |
| Git HEAD | `0ea1f9954d73a36f1d925d93c64c80d8c01b63f1`（計測実装は未コミットの作業ツリー） |
| READY | `READY / development-v1` |
| 計測後の定常メモリ | 約8.094 MiB（ピーク値ではない） |
| 計測前のホストCPU観測 | 約9.7–18.9% |

CPU・メモリのCompose制限がなく、ホストにバックグラウンド負荷があり、実装後の再起動も行われていない。この3点から他実装との正式比較には使用しない。

## データセット

| 項目 | 値 |
| --- | --- |
| dataset_version | `development-v1` |
| manifest SHA-256 | `10FF5D2017557D3B11E42DF1FFA578BE1B6D291990FB6C5F03E2E6BB992E5A17` |
| アクセスログ | 720件 |
| ポリシー | 36件 |
| ルール | 282件 |
| IPグループ | 48件 |
| IPグループメンバー | 72件 |

## 生データ

- [バッチ30回](benchmark-results/development-v1-preliminary-20260718/batch-report.json)
- [逐次・系列1（10回）](benchmark-results/development-v1-preliminary-20260718/sequential-report-1.json)
- [逐次・確認系列（20回）](benchmark-results/development-v1-preliminary-20260718/sequential-report-2.json)

主要な実行コマンドは次のとおり。

```powershell
# READY済みコンテナ内で、run-batchプロセス境界を計測
docker compose exec -T target /benchmark/firewall-priority-indexed benchmark `
  --mode batch `
  --input /benchmark/data/access_logs `
  --expected /benchmark/data/expected_results `
  --output-directory /benchmark/output/benchmark-runs `
  --warmup 5 --runs 30 --cooldown 250ms

# WindowsホストからKeep-Alive・同時実行数1で計測
go run . benchmark `
  --mode sequential `
  --input ../benchmark-data/development-v1/access_logs `
  --expected ../benchmark-data/development-v1/expected_results `
  --server http://127.0.0.1:8080 `
  --warmup 3 --runs 10 --cooldown 500ms
```

## 正式計測への課題

1. 実装をコミットし、PCを再起動する。
2. DockerのCPU数・メモリ上限をComposeで明示する。
3. 電源プランとバックグラウンドプロセスを固定する。
4. `release-v1` を生成し、最大500万ルールで測る。
5. 逐次の二峰性が再現するか確認し、必要ならWindowsホスト経由ではなく同一Dockerネットワーク上の専用クライアントで再計測する。
