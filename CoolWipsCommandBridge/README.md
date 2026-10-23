# CoolWips DiscordSRV Command Bridge

This Paper plugin contributes the CoolWips SMP Bot slash-command definitions to DiscordSRV's supported `SlashCommandProvider` API. DiscordSRV therefore includes these commands whenever it synchronizes guild commands instead of deleting them.

The separate CoolWips SMP Bot process continues to handle the interactions, including voice operations. The bridge contains no Discord token.

This plugin also handles `/version`, `/mc-status`, and `/mc-players`, and provides `/mc-say`, `/mc-time`, and `/mc-weather` for Minecraft. The control commands are restricted to the owner, configured moderator role IDs, or administrators when enabled in the plugin's `config.yml`.

The plugin depends on DiscordSRV and is built against the server's installed Paper and DiscordSRV JARs.

