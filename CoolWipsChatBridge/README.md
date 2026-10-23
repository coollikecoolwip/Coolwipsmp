# CoolWips Chat Bridge

This Paper plugin relays ordinary player chat to one Discord text channel and ordinary human messages in that channel back into Minecraft. It reuses DiscordSRV's already-connected bot, so it has no bot token of its own.

The bridge ignores Minecraft commands (they are not chat events), Discord bots, webhooks, and Discord messages that start with one of the configured command prefixes. Discord slash-command interactions are not ordinary Discord messages and are not relayed.

## Install

1. Build this project with Maven and Java 25. The output JAR is `target/CoolWipsChatBridge.jar`.
2. Put the JAR in the server's `plugins` folder beside DiscordSRV.
3. In DiscordSRV's `config.yml`, disable its own two chat relays to prevent duplicate messages:

   ```yaml
   DiscordChatChannelDiscordToMinecraft: false
   DiscordChatChannelMinecraftToDiscord: false
   ```

4. Leave the `Channels` mapping pointing to the requested channel if desired; the custom bridge uses its own `discord-channel-id` setting in `plugins/CoolWipsChatBridge/config.yml`.
5. Restart the server. Confirm the bot can view and send messages in the selected Discord channel.

The generated plugin config defaults to channel `1523912465384214729`, with both directions enabled. Only one channel is listened to. Console output, join/leave notices, deaths, startup/shutdown messages, and command output are not relayed by this plugin.

