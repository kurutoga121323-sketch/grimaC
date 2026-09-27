# HvHCpsLimiter 1.0.0

Paper 1.21.11用の、HvH向けSurround再設置リミッターです。

Surroundブロックが壊された直後、その同じ座標へ黒曜石などを再設置しようとした時だけ、設定した確率で数tickキャンセルします。全体のCPSを落とすのではなく、break/placeが回っている場所に小さい隙を作って、End Crystalを差し込める時間を作る目的です。

## コマンド

- `/hvhcps reload`: configを再読み込み
- `/hvhcps status`: 現在の対象ブロックと確率を表示

## 初期設定

- 通常の割り込み: 18%
- 同じ座標で短時間に繰り返された時: 35%
- 割り込み時間: 5 ticks

強すぎる場合は `base-chance` / `repeat-chance` / `gap-ticks` を下げてください。Surroundをもっと崩したい場合は `repeat-chance` を先に上げるのがおすすめです。
