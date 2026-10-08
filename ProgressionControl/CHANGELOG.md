# Changelog

All notable changes to CoolWipsProgression are documented here.

## 1.1.4

- Hardened Nether and End protection against entity/vehicle portal travel.
- Boats and other non-player entities cannot enter locked Nether/End dimensions through portals.
- Nether portal creation and Nether portal pairing are blocked while the Nether gate is locked.
- Locked Nether/End respawn destinations are redirected to the overworld.
- Added a repeating dimension safety sweep that teleports normal players out of locked Nether/End worlds, catching direct/plugin-injected world changes and event bypasses.

## 1.1.3

- Fixed the GitHub Actions compile failure caused by passing a Player method reference directly to runTaskTimer.
- The repeating Elytra safety sweep now checks every online player through a valid Runnable.
- Elytra remains permanently disabled and cannot be enabled through configuration or /progression unlock elytra.



## 1.1.2

- Hard-disabled Elytra server-wide.
- Elytra cannot be equipped via armor-slot clicks, hot-swaps, offhand swaps, shift-clicks, dragging, right-click auto-equip, or dispenser armor equip.
- Elytra pickup and Elytra-based interaction are blocked.
- Elytra gliding is always cancelled, regardless of gate state, bypass permission, game mode, or how the Elytra reached the chest slot.
- Added a continuous safety sweep that removes Elytras inserted directly into player chest slots by commands or other plugins.


## 1.1.1

- Fixed locked Elytra flight after chestplate-to-Elytra swaps and other equip bypasses.
- Added a movement-level failsafe that immediately stops gliding when the Elytra gate is locked.
- Added Shulker Box placement, opening, breaking, dispenser, and automation restrictions.
- Hardened armor swap and inventory handling.


## 1.1.0

- Expanded progression from late-game-only gates to a full resource progression system.
- Added gates for iron, copper, gold, redstone, lapis lazuli, and emerald.
- Kept diamonds, Nether, Netherite, End, Elytra, Shulker, Totems, and Tridents.
- Added enchanting and brewing gates.
- Added raw iron, raw gold, and raw copper handling.
- Added ore mining protection for overworld progression materials.
- Added pickup, crafting, placement, interaction, equipment, and consumption checks for supported gated items.
- Added Nether portal ignition and dimension-travel protection.
- Updated plugin metadata and build artifact naming.
- Updated the README and default configuration with the complete gate list and installation/build instructions.
- Hardened the GitHub Actions build to verify the expected JAR before uploading it.

## 1.0.0

- Initial CoolWipsProgression release.
- Added manual lock/unlock commands.
- Added configurable persistent progression gates.
- Added admin and bypass permissions.

