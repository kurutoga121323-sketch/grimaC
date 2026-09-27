# WorldReload 1.1.0

Paper 1.21.11（build 132 を含む）用、Java 21 のワールド定期復元プラグインです。Paper API `1.21.11-R0.1-SNAPSHOT` に対してビルドします。

## ビルドと導入

Java 21 と Maven を入れ、プロジェクト直下で `mvn package` を実行します。生成される `target/WorldReload.jar` をサーバーの `plugins/` に入れて再起動してください。

`plugins/WorldReload/config.yml` の `worlds` に、復元対象の全ワールドを並べます。Nether / End も初期化したい場合は `world_nether` と `world_the_end` を追加します。`evacuation-world` は対象外の、常時ロード済みの退避用ワールドにしてください（例: `lobby`）。対象が `world` のままでは安全に復元できません。

復元時にプレイヤーをキック・切断することはありません。対象ワールドにいるプレイヤーは自動作成される待機ワールドへ一時移動し、復元後に戻ります。バックアップは先に作業領域へ検査・複製され、ロードに失敗した場合は直前のワールドへ自動ロールバックします。直前のワールドデータは `plugins/WorldReload/recovery/` に残ります。

Paper のメインワールド（通常は `server.properties` の `level-name`）は稼働中に安全にアンロードできないため、オンライン復元の対象にはできません。ロビーをメインワールドにし、リセット対象を別のゲーム用ワールドとしてロードしてください。`/worldreload status` で対象がオンライン復元可能か確認できます。

`live-terrain-primary-worlds: true` の場合、メインワールドはアンロードせず、バックアップを一時ワールドとして読み込んでブロックをchunk単位で貼り戻します。プレイヤーは接続したままですが、これは地形向けの復元です。チェストなどの中身やエンティティまで完全に巻き戻す用途では、サーバー停止中のフォルダ差し替えを使ってください。

小さいアリーナなのに復元chunk数が多い場合は、バックアップ内に昔探索された遠いchunkが残っています。`live-terrain-bounds.enabled: true` にして中心座標と半径を指定すると、その範囲のchunkだけ復元します。

## コマンド

- `/worldreload save <name>`: 設定されたワールドを `plugins/WorldReload/backups/<name>/` に保存。
- `/worldreload use <name>`: 定期復元で使う保存名を選択。
- `/worldreload time 2h`: 2 時間ごとの復元を開始。`30m`、`1d`、`45s` も可。
- `/worldreload restore [name]`: 今すぐ復元。
- `/worldreload cancel`: 定期復元を停止。
- `/worldreload list`: 保存済みバックアップを表示。
- `/worldreload status`: 対象ワールドが安全にオンライン復元できるか確認。

`time` の設定は `config.yml` に保存され、サーバー再起動後にも自動で再開します。

## 既存バックアップの取り込み

既存バックアップの親フォルダに `world/`、`world_nether/` のような各ワールドフォルダがある状態で、管理者が以下を実行します。

`/worldreload import <name> <親フォルダの絶対パス>`

たとえば `D:\\mc-backups\\2026-09-24` の直下に `world` があるなら、`/worldreload import before-event D:\\mc-backups\\2026-09-24` です。取り込んだ後、`/worldreload use before-event`、`/worldreload time 2h` と実行します。

手動で `plugins/WorldReload/backups/<name>/<world名>/` に置いたバックアップも `use` で直接選択できます。復元はワールドフォルダを完全に置換するため、実運用前にサーバー全体の外部バックアップを必ず作成してください。
