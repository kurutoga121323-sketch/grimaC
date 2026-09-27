# GimaC source archive

これまで作成・修正してきたPaper / Velocity向けプラグインのソースをフォルダーごとに整理したものです。

## Folders

- `DuelClans` - Rating / clan / kill message 系のPaperプラグイン
- `GrimVersion-Velocity` - Velocity版のGrimVersionソース
- `GrimVersion-Paper` - Paper版のF3 Server Brand変更プラグイン
- `HvHCpsLimiter` - HvH用surround直後の再配置制限プラグイン
- `UniqueKits` - Paper + Velocity Bridge構成のKitプラグイン。oversized `uniquekits:kits_sync` 対策済み
- `WorldReload` - ワールド復元 / 定期地形リセット用Paperプラグイン
- `GrimAC-2.3.74-12449c4-OversizedPayloadPatch` - GrimAC 2.3.74-12449c4 に oversized custom payload fail-open / 診断ログを追加したソース

## Notes

`target`, `build`, `.gradle`, `.git`, compiled `.class`, generated `.jar` はコピー時に除外しています。必要なら各フォルダー内で Maven または Gradle の build を実行してください。
