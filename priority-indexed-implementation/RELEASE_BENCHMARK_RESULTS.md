# release-v1 性能参考計測結果

> [!WARNING]
> この結果の構成はbboltマスタDBとデータ処理が同一プロセス・同一コンテナで動作するため、追加されたDBプロセス・コンテナ分離要件には未適合である。以下の値は順位対象外の参考値として扱う。

この結果では、bboltマスタDBを正本とし、提供CSVは空DBへの初回投入だけに使用した。DB永続化と管理CRUDの機能は持つが、現在必須のプロセス・コンテナ分離は行っていない。

## スコア

`priority-indexed-implementation` を、20,000テナント・5,122,280ルール・25,000アクセスの `release-v1` で計測した。代表値は各runの中央値である。

| 部門 | ウォームアップ | 本計測 | 主スコア中央値 | スループット | 参考値 |
| --- | ---: | ---: | ---: | ---: | --- |
| バッチ | 5回 | 30回 | **135.845 ms / 25,000件** | **184,034件/秒** | 最小119.167 ms、p95 179.180 ms、最大209.476 ms |
| 逐次 | 3回 | 10回 | **15.880 s / 25,000件** | **1,574件/秒** | run別p50中央値529.20 µs、p99中央値1.079 ms、最大値中央値3.004 ms |

## システム構成

| 項目 | 内容 |
| --- | --- |
| 構成要素 | Go単一プロセス、bboltマスタDB、オンメモリ優先度索引、HTTP/1.1評価・管理API |
| マスタDB | **bbolt 1.5.0（組み込みKey-Value DB、永続ファイル4,320,145,408 bytes）** |
| 外部ミドルウェア | なし（Redis、MySQL、PostgreSQL、外部KVSを不使用） |
| 使用言語 | Go 1.25.12（linux/amd64） |
| 入出力 | マスタCSVを初回だけDBへ変換投入、アクセスログはParquet、バッチ結果はCSV、逐次・管理APIはJSON |
| 正規表現 | Go標準RE2互換エンジン |
| 配備 | Debian bookworm-slimベースの単一Dockerコンテナ |
| 検索方式 | 完全一致ソート配列、接頭辞バケット、IPグループ逆引き、適応型時刻索引、一般RE2フォールバック |

DBではポリシー・ルール・グループを型付きJSON、IPv4メンバーを複合キーで保持する。文書化した管理CRUD APIから個別に変更でき、更新トランザクション後にDBから新しい索引スナップショットを構築してアトミックに切り替える。成功後は `data_version` のリビジョンが増え、制約違反時はDB変更をロールバックする。

各条件種別から一致する最小priority候補を求め、候補全体の最小priorityを選ぶことでFirst Matchと同じ結果を返す。DB初期投入、DBからのマスタ取得、索引構築、正規表現コンパイルはREADY前に完了し、計測対象外である。release-v1の新規DBでは起動からREADYまで約8分、計測後の常駐メモリは約1.57 GiBだった。

## 正確性

独立した参照実装でrelease-v1の25,000件の正解を生成し、参照実装自身で再評価して全件一致を確認した。正解CSVのSHA-256は `DDBD97D70E328E6BB51811D5BD31105CEA78032D4B31BAAB795E7FB370290B36` である。

| 対象 | 照合量 | 結果 |
| --- | ---: | --- |
| バッチ（ウォームアップを含む35 CSV） | 875,000結果 | 全件一致 |
| 逐次（ウォームアップを含む13パス） | 325,000レスポンス | 全件一致 |
| 合計 | 1,200,000判定 | **不一致0件** |

正式ターゲットには `expected_results` をマウントしていない。バッチ終了後、Windows側の別プロセスで出力CSVを正解照合した。逐次クライアントもWindows側でレスポンス受信時間を停止した後に照合した。

## バッチ分布

`/benchmark/run-batch` のプロセス生成直前から、結果CSVをflushして終了コード0で終了するまでをコンテナ内で計測した。照合時間は含まない。

| 統計 | 25,000件の処理時間 |
| --- | ---: |
| 最小 | 119.167 ms |
| 中央値 | **135.845 ms** |
| 平均 | 140.611 ms |
| p95 | 179.180 ms |
| 最大 | 209.476 ms |
| 標準偏差（母標準偏差） | 18.512 ms |

## 逐次分布

HTTP/1.1 Keep-Alive、同時実行数1、HTTPパイプライニングなしで計測した。TCP接続はREADY取得で事前確立した。全件時間は25,000リクエストの送信開始からレスポンス本文受信完了までの窓を合計した値で、JSON照合時間は含まない。

| 統計 | 全件時間 | run別p50 | run別p99 | run別最大値 |
| --- | ---: | ---: | ---: | ---: |
| 最小 | 15.790 s | 528.80 µs | 1,074.10 µs | 2,098.10 µs |
| 中央値 | **15.880 s** | **529.20 µs** | **1,078.55 µs** | **3,003.75 µs** |
| 平均 | 15.922 s | 529.40 µs | 1,080.15 µs | 4,658.50 µs |
| p95 | 16.198 s | 530.80 µs | 1,088.80 µs | 13,699.70 µs |
| 最大 | 16.198 s | 530.80 µs | 1,088.80 µs | 13,699.70 µs |

## データセット

| 項目 | 値 |
| --- | --- |
| dataset_version | `release-v1` |
| manifest SHA-256 | `F68E26416CC8429720E96D10A5B5AC1D7372819A1245A7E62B54FBDDBC87764D` |
| テナント | 20,000 |
| ポリシー | 21,000 |
| ファイアウォールルール | 5,122,280 |
| IPグループ / メンバー | 22,000 / 42,000 |
| アクセスログ | 25,000 |

## 計測環境

| 項目 | 値 |
| --- | --- |
| 計測日時 | 2026-07-18 23:14–23:19 JST |
| PC再起動 | 2026-07-18 22:09:30 JST |
| OS | Windows 11 Pro 10.0.26200 |
| CPU | AMD Ryzen 7 5700X、8コア / 16スレッド |
| 物理メモリ | 51,447,345,152 bytes（約47.91 GiB） |
| 電源プラン | バランス |
| Docker | Client 28.3.3-rd / Server 29.6.1 |
| Dockerエンジン割当 | 16 CPU、25,153,425,408 bytes |
| ターゲット制限 | **8 CPU、16 GiB** |
| 計測後の定常メモリ | 約1.57 GiB / 16 GiB（ピーク値ではない） |
| DockerイメージID | `sha256:60e2a60322c52b7e64efac71a6fada64cd020a0c99d7ef7c4da489f93bac19dd` |
| Git HEAD | `ec0353e9f87bed4b3410d3b7b1ca3ce34d0fb920`（実装は未コミット作業ツリー、イメージIDで固定） |
| ホスト公開ポート | 18080（コンテナ内は仕様どおり8080） |

## 実行コマンド

READY後に、バッチ計測ドライバをターゲットコンテナ内、逐次計測ドライバと正解データをホスト側に置いて実行した。

```powershell
docker exec priority-indexed-implementation-target-1 `
  /benchmark/firewall-priority-indexed benchmark `
  --mode batch `
  --input /benchmark/input/access_logs `
  --batch-executable /benchmark/run-batch `
  --output-directory /benchmark/output/release-v1-bbolt-20260718/batch `
  --report /benchmark/output/release-v1-bbolt-20260718/batch-report.json `
  --warmup 5 --runs 30 --cooldown 250ms

go run . benchmark `
  --mode sequential `
  --input ../benchmark-data/release-v1/access_logs `
  --expected ../benchmark-data/release-v1/expected_results `
  --server http://127.0.0.1:18080 `
  --report output/release-v1-bbolt-20260718/sequential-report.json `
  --warmup 3 --runs 10 --cooldown 500ms
```

## 生データ

- [バッチ30回](../benchmark-results/release-v1-bbolt-20260718/batch-report.json)
- [バッチ全CSV照合](../benchmark-results/release-v1-bbolt-20260718/batch-validation.json)
- [逐次10回](../benchmark-results/release-v1-bbolt-20260718/sequential-report.json)
