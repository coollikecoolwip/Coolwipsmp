# CoolWipsProgression Development Notes

Current version: 1.1.1

## Versioning rule

Every bug fix increments the patch version by 1:
1.1.0 -> 1.1.1 -> 1.1.2 -> 1.1.3

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
- Locked Elytra cannot be equipped through the protected inventory paths.
- EntityToggleGlideEvent blocks starting Elytra gliding.
- PlayerMoveEvent is a second failsafe that immediately stops gliding if the Elytra gate is locked, including cases where the Elytra was already equipped.

Shulker:
- Shulker shells and Shulker boxes are gated.
- Locked Shulker boxes cannot be placed, opened, or broken by normal players.
- Dispensers cannot place locked Shulker boxes.
- Inventory automation involving a Shulker Box inventory is blocked while the gate is locked.

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
