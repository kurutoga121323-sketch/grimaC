# GrimVersion Paper

Paper版のGrimVersionは、MinecraftクライアントのF3に表示されるServer Brandだけを変更するPaperプラグインです。

表示例:

```text
Ez - GrimHvH
```

実際に送信するbrand文字列:

```text
§fEz - §cGrimHvH
```

## Requirements

- Java 21
- Paper 1.21.11
- Minecraft 1.21.11

## Install

1. `GrimVersion-Paper-1.0.0.jar` をPaperサーバーの `plugins/` に入れます。
2. サーバーを再起動します。
3. 必要なら `plugins/GrimVersion/config.yml` を編集します。
4. `/grimversion reload` で再読み込みします。

## Configuration

```yaml
brand:
  enabled: true
  prefix: "Ez - "
  text: "GrimHvH"
  prefix-color: "f"
  text-color: "c"
```

`prefix-color` と `text-color` はMinecraft legacy color codeの1文字です。例: `f`, `c`, `9`, `b`, `7`。

## Commands

```text
/grimversion reload
/grimversion brand
```

Permissions:

```text
grimversion.reload
grimversion.brand
```

## Notes

このPaper版はPaperサーバー単体でF3 Brandを変更したい場合に使います。Velocity側で統一して変更したい場合はVelocity版をVelocityの `plugins/` に入れてください。
