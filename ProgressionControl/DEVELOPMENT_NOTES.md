# CoolWipsProgression Development Notes

Current version: 1.1.9

## Versioning rule

Every bug fix increments the patch version by 1:
1.1.5 -> 1.1.6 -> 1.1.7 -> 1.1.8 -> 1.1.9

Keep the version synchronized in:
- ProgressionControl/pom.xml
- ProgressionControl/src/main/resources/plugin.yml
- ProgressionControl/src/main/resources/config.yml
- ProgressionControl/README.md
- ProgressionControl/CHANGELOG.md

The GitHub Actions workflow reads the Maven project version automatically.

## Current progression protections

Armor:
- Normal armor-slot equip
- Number-key hot swaps
- Offhand swaps
- Shift-click auto-equip
- Inventory dragging into armor slots
- Right-click armor equip
- Dispenser armor equip

Elytra:
- Elytra is permanently hard-disabled server-wide.
- The Elytra gate is forcibly kept false during config loading and cannot be unlocked with /progression unlock elytra.
- The bypass permission and creative/spectator modes cannot enable Elytra.
- Pickup, right-click use, normal armor-slot equip, number-key hot-swap, offhand swap, shift-click, inventory drag, and dispenser armor equip are blocked.
- EntityToggleGlideEvent always blocks starting Elytra gliding.
- PlayerMoveEvent immediately stops any active glide.
- A repeating server-side safety sweep checks every online player every tick and removes Elytras from chest slots inserted by commands or other plugins.

Dimensions:
- Player teleport into locked Nether/End is blocked.
- Player portal travel into locked Nether/End is blocked by the player teleport layer.
- Non-player portal entities, including boats carrying players, cannot enter locked Nether/End.
- Nether portal creation and Nether portal pairing are blocked while Nether is locked.
- Respawn locations inside locked Nether/End are redirected to the overworld.
- Unlocked Nether/End respawns retain their original bed or anchor destination.
- Dimension ejections and redirected respawns use the overworld spawn, avoiding unsafe coordinates copied from another dimension.
- If no overworld is loaded, the plugin does not teleport a player back into a locked dimension as a fallback.
- A repeating server-side safety sweep checks every online player every tick and immediately teleports players out of locked Nether/End worlds, including direct/plugin-injected world changes.

Shulker:
- Shulker shells and Shulker boxes are gated.
- Both plain and dyed Shulker boxes are covered by pickup, crafting, placement, breaking, and dispenser checks.
- Locked Shulker boxes cannot be placed, opened, or broken by normal players.
- Dispensers cannot place locked Shulker boxes.
- Inventory automation involving a Shulker Box inventory is blocked while the gate is locked.

Totems:
- EntityResurrectEvent blocks locked Totems from either hand.
- Unlocked Totems, progression bypass, and resurrections without a Totem retain their normal behavior.

Regression tests:
- `mvn -B test` exercises dimension respawns/ejection, plain/dyed Shulker checks, and Totem resurrection events without requiring a live server.

## Build

GitHub Actions workflow:
.github/workflows/build-progression.yml

The workflow:
1. Sets up Java 25.
2. Reads project.version from Maven.
3. Builds the plugin.
4. Verifies the expected JAR.
5. Uploads the JAR as an Actions artifact.
6. Commits the built JAR to:
   ProgressionControl/CoolWipsProgression-<version>.jar

The workflow does not trigger from the generated JAR commit, preventing an endless build loop.
