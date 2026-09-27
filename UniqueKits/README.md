# UniqueKits 1.1.2

Java 21 / Paper 1.21.11 / Velocity 3.4用のMavenマルチモジュールプロジェクトです。

## ビルド

このディレクトリで `mvn package` を実行します。

- `paper/target/UniqueKits-1.1.2.jar`: すべてのPaperバックエンドへ導入
- `velocity/target/UniqueKits-VelocityBridge-1.1.2.jar`: Velocityへ導入

両方のJARを交換してからVelocityと全バックエンドを再起動し、Kit Creatorにするバックエンド内で`/setkitcreator`を1回実行してください。Velocityに登録したバックエンド名は自動検出され、`plugins/uniquekits-bridge/creator-server.txt`へ保存されます。

Kit Creator内で死亡してリスポーンしたプレイヤーは、Paper側の`main-server`設定（初期値`main`）のVelocityサーバーへ戻されます。

## Kit同期

VelocityがKitデータの正本を保持します。Paperからの作成・削除はKit単位で送信されるため、別バックエンド上の変更を古い全体データで上書きしません。バックエンドは30秒ごとに最新スナップショットを取得します。

旧Bridgeの`shared-kits.bin`が存在する場合は、最初に接続したPaperサーバーを通して自動的に新形式の`kits-v2.bin`へ移行します。更新前にVelocityの`plugins/UniqueKitsBridge/`と各Paperサーバーの`plugins/UniqueKits/`をバックアップしてください。

## 管理

- `/setkitcreator`: 現在地と現在のバックエンドをKit Creatorとして登録
- `/kitcreator`: Kit Creatorバックエンドへ移動
- `/createukit [public] <name>`: 現在のインベントリを保存
- `/deleteukit [public] <name>`: Kitを削除
- 木のツルハシでCreatorワールド内のチェストまたは樽を右クリック: 無限コンテナとして登録（管理者のみ）

戦闘に持ち込めるシュルカーボックスは1個までです。超過時はアイテムを削除せず、攻撃をキャンセルして本人へ通知します。

Kitは選択した瞬間から死亡するまで再選択できません。攻撃の有無には依存しないため、殴らない立ち回りや長いロード中に別Kitへ切り替える抜け道はありません。
