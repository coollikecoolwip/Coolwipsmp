# CoolWips Progression Control

**CoolWipsProgression 1.1.5** is a standalone Paper plugin for CoolWips SMP that lets server staff manually control the server's survival progression.

Every gate starts **locked** until an admin unlocks it. Unlocks are stored in the plugin configuration and survive server restarts.

## Requirements

- Paper 26.2
- Java 25
- No dependency on LuckPerms, Vault, DiscordSRV, Geyser, or the CoolWips economy plugin

## Commands

```text
/progression status
/progression unlock <gate>
/progression lock <gate>
/progression reload
```

`/prog` is an alias for `/progression`.

Examples:

```text
/progression status
/progression unlock iron
/progression unlock diamonds
/progression unlock nether
/progression lock nether
/progression reload
```

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| `coolwipsprogression.admin` | OP | View and manage progression gates |
| `coolwipsprogression.bypass` | false | Bypass every progression gate |

Creative and spectator players also bypass normal progression restrictions. Elytra is the exception: it is hard-disabled even for bypass, creative, and spectator players.

## Gates

The current gates are:

```text
iron
copper
gold
redstone
lapis
emerald
diamonds
nether
netherite
enchanting
brewing
end
elytra
shulker
totems
tridents
```

### Resource and equipment gates

**Iron**
- Iron ore and deepslate iron ore mining
- Iron ingots, nuggets, raw iron, and iron blocks
- Iron tools and armor
- Pickup, crafting, placing, and use of gated items

**Copper**
- Copper ore and deepslate copper ore mining
- Copper ingots, raw copper, copper blocks, and copper-family items
- Pickup, crafting, placing, and use of gated items

**Gold**
- Gold ore and deepslate gold ore mining
- Gold ingots, nuggets, raw gold, gold blocks
- Gold tools and armor
- Pickup, crafting, placing, and use of gated items

**Redstone**
- Redstone ore and deepslate redstone ore mining
- Redstone dust, blocks, and redstone torches
- Pickup, crafting, placing, and use of gated items

**Lapis Lazuli**
- Lapis ore and deepslate lapis ore mining
- Lapis and lapis blocks
- Pickup, crafting, placing, and use of gated items

**Emerald**
- Emerald ore and deepslate emerald ore mining
- Emeralds and emerald blocks
- Pickup, crafting, placing, and use of gated items

**Diamonds**
- Diamond ore and deepslate diamond ore mining
- Diamonds and diamond blocks
- Diamond tools and armor
- Pickup, crafting, placing, and use/equipping of gated items

### Dimension and late-game gates

**Nether**
- Nether portal ignition
- Player travel into the Nether
- Protection against being moved into the Nether by teleport events

**Netherite**
- Ancient debris mining
- Netherite ingot/scrap/block handling
- Netherite tools and armor
- Netherite crafting and smithing

**Enchanting**
- Opening/using enchanting tables

**Brewing**
- Opening/using brewing stands

**The End**
- End portal travel
- End portal frame interaction

**Elytra**
- Permanently disabled server-wide; the Elytra gate and bypass permission cannot enable it.
- Elytra pickup is blocked.
- Elytra cannot be equipped through armor slots, right-click, number-key hot-swap, offhand swap, shift-click, inventory drag, or dispenser armor equip.
- Starting Elytra gliding is always blocked.
- A continuous server-side safety sweep removes Elytras from chest slots, including Elytras inserted by commands or other plugins.

**Shulker**
- Shulker shells
- Shulker boxes
- Pickup, crafting, placing, and use of gated items

**Totems**
- Totem pickup and use/consumption handling

**Tridents**
- Trident pickup and use handling

## How progression works

1. Install the plugin.
2. Restart the server.
3. Confirm the starting state with `/progression status`.
4. Unlock gates as the SMP reaches each milestone.
5. Use `/progression lock <gate>` to re-lock a stage when necessary.
6. Use `/progression reload` after editing `plugins/CoolWipsProgression/config.yml`.

All gate values are boolean. For example:

```yaml
gates:
  iron: true
  copper: true
  gold: true
  redstone: true
  lapis: true
  emerald: true
  diamonds: true
  nether: true
```

## Important behavior

The plugin is designed to stop normal-player progression through the event paths it controls. It does **not** attempt to remove or confiscate already-owned items, and it does not override operator/creative/spectator privileges.

Other plugins or administrator commands can still place an Elytra in a player's normal inventory, but the plugin prevents it from being equipped or used. Direct chest-slot insertion is removed by the server-side safety sweep.

## Installation

Copy:

```text
CoolWipsProgression-1.1.5.jar
```

into:

```text
plugins/
```

Then restart Paper.

The generated configuration is:

```text
plugins/CoolWipsProgression/config.yml
```

## Building

From the `ProgressionControl` directory:

```text
mvn -B clean package
```

The output is:

```text
target/CoolWipsProgression-1.1.5.jar
```

The repository also contains a GitHub Actions build workflow at:

```text
.github/workflows/build-progression.yml
```

It builds with Java 25 and uploads the JAR as a workflow artifact.

## Version history

See [CHANGELOG.md](CHANGELOG.md) for release notes.


## Bug-fix versioning

Each bug fix increments the patch version by 1 (for example, 1.1.0 -> 1.1.1 -> 1.1.5). The GitHub Actions build reads the Maven version automatically, uploads the matching JAR, and commits the built JAR to the root of `ProgressionControl/`.
