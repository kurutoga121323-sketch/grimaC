# GrimVersion

GrimVersion is a small Velocity plugin that rewrites only the F3 Server Brand shown by Minecraft clients.

Display example:

```text
Ez - GrimHvH
```

The default payload text sent to the client is:

```text
§fEz - §cGrimHvH
```

GrimVersion handles only the `minecraft:brand` plugin message. It does not modify server list pings, MOTD, favicon, player counts, sample hover, server list version, protocol number, TAB, or MiniMOTD output.

## Requirements

- Java 21
- Velocity 3.4.x
- Minecraft 1.21.11
- Linux or Windows build environment

## Build

```bash
./gradlew build
```

The plugin jar is generated in `build/libs/`.

## Install

1. Copy `build/libs/GrimVersion-1.0.0.jar` into Velocity's `plugins/` folder.
2. Start or restart Velocity.
3. Edit `plugins/grimversion/config.toml` if needed.
4. Use `/grimversion reload` after changing the config.

## Configuration

`plugins/grimversion/config.toml`

```toml
[brand]
enabled = true
prefix = "Ez - "
text = "GrimHvH"
prefix-color = "f"
text-color = "c"
```

`prefix` and `text` are joined to make the visible F3 brand. `prefix-color` and `text-color` are single-character Minecraft legacy color codes. For example, `f`, `c`, `9`, `b`, and `7` are valid.

Default result:

```text
§fEz - §cGrimHvH
```

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

`/grimversion reload` reloads `config.toml` and replies:

```text
GrimVersion configuration reloaded.
```

`/grimversion brand` shows the currently configured brand.

## MiniMOTD and TAB compatibility

GrimVersion leaves the server list untouched. MiniMOTD can manage MOTD, favicon, player count, sample hover, and other server list display features.

GrimVersion only intercepts backend server-to-player `minecraft:brand` messages and sends the configured brand again after a player connects to a backend server, so the same F3 brand is kept when moving between backend servers such as `main`, `kitcreator`, and `5b5t`.

