# LiteminerBukkit

A server-side [Paper](https://papermc.io/)/Bukkit plugin that brings [Liteminer](https://github.com/iamkaf/liteminer) vein-mining functionality to your server — **no server-side mod required**.

Players who have the Liteminer mod installed can use all its shapes seamlessly. Players without the mod are completely unaffected.

---

## Features

| Shape | Description |
|---|---|
| **Shapeless** | Flood-fill up to 64 connected same-type blocks (DFS, Manhattan-distance ordered — matches client highlight exactly) |
| **Small Tunnel** | 1×1 tunnel straight ahead in the direction the player is looking |
| **Staircase Up** | Diagonal upward staircase (3 blocks tall per step) |
| **Staircase Down** | Diagonal downward staircase (3 blocks tall per step) |
| **3×3** | 3×3 plane perpendicular to the face the player is looking at |

### Additional behaviour (matching the original mod)

- **BlockFamily ore matching** — `COAL_ORE` ↔ `DEEPSLATE_COAL_ORE`, `IRON_ORE` ↔ `DEEPSLATE_IRON_ORE`, etc. are treated as the same ore family in Shapeless mode
- **Tool durability consumption** — each extra block consumes 1 durability (instant-break / hardness-0 blocks are exempt)
- **Tool break prevention** — vein-mining stops automatically when the tool has ≤ 2 durability remaining
- **Food exhaustion** — each extra block applies a small exhaustion penalty, matching vanilla hunger mechanics
- **Creative mode** — creative players skip durability and exhaustion, blocks simply vanish

---

## Requirements

| Component | Version |
|---|---|
| **Minecraft** | 26.1.2 (Paper) |
| **Paper API** | `26.1.2.build.66-stable` |
| **Java** | 25 |
| **Client mod** | [Liteminer](https://github.com/iamkaf/liteminer) (optional per player) |

> Players without Liteminer installed are completely unaffected — the plugin only activates when it receives a packet from the mod.

---

## Installation

1. Download the latest `LiteminerBukkit-*.jar` from [Releases](../../releases).
2. Drop it into your server's `plugins/` folder.
3. Restart the server.

No configuration file is needed.

---

## How it works

Liteminer communicates with the server through Minecraft's [Plugin Messaging](https://wiki.vg/Plugin_channels) system.

When a player holds the vein-mine key, the mod sends a small packet on the channel `liteminer:main/c2sveinminekeybindchange` containing:

| Byte | Meaning |
|---|---|
| `byte[0]` | Key state (`1` = held, `0` = released) |
| `byte[1–4]` | Shape index (big-endian `int`) |

The plugin stores this state per player. When a `BlockBreakEvent` fires for a player with the key held, the plugin walks the selected shape and breaks the collected blocks server-side.

---

## Building from source

```bash
# Requires Java 25 (install via SDKMAN: sdk install java 25-open)
export SDKMAN_DIR="$HOME/.sdkman"
source "$SDKMAN_DIR/bin/sdkman-init.sh"
sdk use java 25-open

./gradlew build
# Output: build/libs/LiteminerBukkit-*.jar
```

---

## License

[MIT](LICENSE)

---

## Credits

- [iamkaf/liteminer](https://github.com/iamkaf/liteminer) — the original mod this plugin is based on
