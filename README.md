# CoolWips Custom Plugins

This repository contains custom plugins for CoolWips SMP.

## Plugins

### CoolWips Economy
Located in `CoolWipsEconomy/`.

- Paper 26.2 / Java 25
- DiscordSRV account linking
- UnbelievaBoat economy integration
- GitHub-managed sell prices
- Operator-only sale maintenance controls

### CoolWips Progression Control
Located in `ProgressionControl/`.

- Paper 26.2 / Java 25
- Staff-controlled survival progression gates
- Persistent unlock state and hard-disabled Elytra

### CoolWips Chat Bridge 1.0.0
Located in `CoolWipsChatBridge/`.

- Paper 26.2 / Java 25
- Relays player chat and one Discord text channel through DiscordSRV
- No separate bot token

### CoolWips Command Bridge 1.2.4
Located in `CoolWipsCommandBridge/`.

- Paper 26.2 / Java 25
- Keeps the standalone bot's Discord slash commands available through DiscordSRV
- Provides Minecraft status and owner-controlled server commands

GitHub Actions builds all four plugins, uploads JAR artifacts, and keeps the current JARs in the repository.
