# CoolWips Progression Control

Standalone Paper 26.2 progression plugin for CoolWips SMP.

## Commands

/progression status
/progression unlock <gate>
/progression lock <gate>
/progression reload

## Gates

diamonds, nether, netherite, end, elytra, shulker, totems, tridents

All gates are locked by default. Unlocks are stored in config.yml and survive restarts.

Locked progression is enforced against normal players:
- Diamonds: diamond ore mining, diamond pickups, crafting and equipment use
- Nether: Nether portal ignition and dimension travel
- Netherite: ancient debris mining, Netherite crafting/smithing and equipment use
- End: End portal travel
- Elytra: Elytra crafting/use/equipping
- Shulker: shulker shells and shulker boxes
- Totems: totem use
- Tridents: trident use

Operators receive coolwipsprogression.admin. Players with coolwipsprogression.bypass bypass every gate.

## Build

Run mvn clean package in this directory. The jar is target/CoolWipsProgression-1.0.0.jar.
