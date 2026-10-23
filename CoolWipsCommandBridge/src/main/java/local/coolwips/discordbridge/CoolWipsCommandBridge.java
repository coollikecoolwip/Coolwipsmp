package local.coolwips.discordbridge;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.commands.PluginSlashCommand;
import github.scarsz.discordsrv.api.commands.SlashCommandProvider;
import github.scarsz.discordsrv.api.events.GuildSlashCommandUpdateEvent;
import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.SlashCommandEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Member;
import github.scarsz.discordsrv.dependencies.jda.api.Permission;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.OptionType;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.build.CommandData;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.build.OptionData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class CoolWipsCommandBridge extends JavaPlugin implements SlashCommandProvider {
    private static final Set<Long> OWNER_USER_IDS = Set.of(1025176521054355537L);
    private long enabledAtMillis;

    private final ListenerAdapter versionCommandListener = new ListenerAdapter() {
        @Override
        public void onSlashCommand(SlashCommandEvent event) {
            if (!event.isFromGuild()) {
                return;
            }
            if (Set.of("version", "mc-status", "mc-players", "mc-say", "mc-time", "mc-weather")
                    .contains(event.getName()) && !hasMinecraftAccess(event)) return;
            switch (event.getName()) {
                case "version" -> event.reply("Minecraft server version: `" + Bukkit.getVersion() + "`")
                        .setEphemeral(true).queue();
                case "mc-status" -> minecraftStatus(event);
                case "mc-players" -> minecraftPlayers(event);
                case "mc-say" -> minecraftSay(event);
                case "mc-time" -> minecraftTime(event);
                case "mc-weather" -> minecraftWeather(event);
                default -> { }
            }
        }
    };

    private BukkitTask listenerRegistrationTask;

    @Override
    public void onEnable() {
        enabledAtMillis = System.currentTimeMillis();
        saveDefaultConfig();
        DiscordSRV.api.subscribe(this);
        getLogger().info("Minecraft command handlers enabled; the standalone bot owns slash-command publishing.");
        listenerRegistrationTask = getServer().getScheduler().runTaskTimer(this, () -> {
            DiscordSRV discordSrv = DiscordSRV.getPlugin();
            JDA jda = discordSrv == null ? null : discordSrv.getJda();
            if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
                jda.addEventListener(versionCommandListener);
                listenerRegistrationTask.cancel();
                getLogger().info("Registered /version and Minecraft command handlers without publishing commands.");
            }
        }, 1L, 20L);
    }

    @Override
    public void onDisable() {
        if (listenerRegistrationTask != null) {
            listenerRegistrationTask.cancel();
        }
        DiscordSRV discordSrv = DiscordSRV.getPlugin();
        JDA jda = discordSrv == null ? null : discordSrv.getJda();
        if (jda != null) {
            jda.removeEventListener(versionCommandListener);
        }
        DiscordSRV.api.unsubscribe(this);
    }

    @Subscribe
    public void keepDiscordSrvCommandSyncAdditive(GuildSlashCommandUpdateEvent event) {
        // DiscordSRV's bulk update replaces every command for the guild. This bot must
        // add or update its own commands without removing commands owned by other plugins.
        event.setCancelled(true);
    }

    @Override
    public Set<PluginSlashCommand> getSlashCommands() {
        Set<PluginSlashCommand> commands = new LinkedHashSet<>();
        for (CommandData command : commandDefinitions()) {
            commands.add(new PluginSlashCommand(this, command));
        }
        return commands;
    }

    private static List<CommandData> commandDefinitions() {
        return List.of(
                command("help", "Show the bot's commands"),
                command("ping", "Show the bot's Discord connection latency"),
                command("uptime", "Show how long the bot has been running"),
                command("server-info", "Show basic server information"),
                command("version", "Show the current Minecraft server version"),
                command("mc-status", "Show the Minecraft server status"),
                command("mc-players", "List players currently online in Minecraft"),
                command("mc-say", "Send a message to Minecraft chat",
                        option(OptionType.STRING, "message", "Message to broadcast in Minecraft", true)),
                command("mc-time", "Set the time in every Minecraft world",
                        choiceOption(OptionType.STRING, "preset", "Time of day", true,
                                "day", "noon", "night", "midnight")),
                command("mc-weather", "Set the weather in every Minecraft world",
                        choiceOption(OptionType.STRING, "weather", "Weather to set", true,
                                "clear", "rain", "thunder")),
                command("join", "Join the voice channel you are currently in"),
                command("leave", "Leave the current voice channel"),
                command("voice-status", "Show the bot's voice connection status"),
                command("tts", "Speak a short message in voice (owner only)",
                        option(OptionType.STRING, "message", "Text to speak (up to 350 characters)", true)),
                command("stop-tts", "Stop the current voice message (owner only)"),
                command("say", "Post a message in this channel as the bot",
                        option(OptionType.STRING, "message", "Message for the bot to post", true)),
                command("announce", "Post a polished announcement embed",
                        option(OptionType.STRING, "title", "Announcement title", true),
                        option(OptionType.STRING, "message", "Announcement text", true)),
                command("poll", "Post a yes-or-no community poll",
                        option(OptionType.STRING, "question", "Question for the poll", true)),
                command("stop", "Stop the bot (owner only)"),
                command("reboot", "Restart the bot (owner only)"),
                command("restart", "Restart the bot (owner only)"),
                command("timeout", "Time out a member for a number of minutes",
                        option(OptionType.USER, "member", "Member to time out", true),
                        option(OptionType.INTEGER, "minutes", "Duration in minutes (1 to 40320)", true),
                        option(OptionType.STRING, "reason", "Reason for the timeout", false)),
                command("untimeout", "Remove a member's timeout",
                        option(OptionType.USER, "member", "Member to un-timeout", true),
                        option(OptionType.STRING, "reason", "Reason for removing the timeout", false)),
                command("kick", "Remove a member from the server",
                        option(OptionType.USER, "member", "Member to kick", true),
                        option(OptionType.STRING, "reason", "Reason for the kick", false)),
                command("ban", "Ban a member from the server",
                        option(OptionType.USER, "member", "Member to ban", true),
                        option(OptionType.STRING, "reason", "Reason for the ban", false),
                        option(OptionType.INTEGER, "delete_days", "Delete recent messages (0 to 7 days)", false)),
                command("unban", "Remove a ban using the user's Discord ID",
                        option(OptionType.STRING, "user_id", "Discord ID of the banned user", true),
                        option(OptionType.STRING, "reason", "Reason for removing the ban", false)),
                command("purge", "Delete recent messages in this channel",
                        option(OptionType.INTEGER, "count", "Number of recent messages (1 to 100)", true)),
                command("slowmode", "Set slowmode for this text channel",
                        option(OptionType.INTEGER, "seconds", "Delay in seconds (0 turns slowmode off)", true)),
                command("warn", "Add a moderator warning to a member",
                        option(OptionType.USER, "member", "Member to warn", true),
                        option(OptionType.STRING, "reason", "Reason for the warning", true)),
                command("warnings", "Show a member's warning history",
                        option(OptionType.USER, "member", "Member to check", true)),
                command("clear-warnings", "Clear a member's warning history",
                        option(OptionType.USER, "member", "Member to clear", true)),
                command("userinfo", "Show information about a server member",
                        option(OptionType.USER, "member", "Member to look up", true))
        );
    }

    private static CommandData command(String name, String description, OptionData... options) {
        return new CommandData(name, description).addOptions(options);
    }

    private static OptionData option(OptionType type, String name, String description, boolean required) {
        return new OptionData(type, name, description, required);
    }

    private static OptionData choiceOption(OptionType type, String name, String description,
                                           boolean required, String... choices) {
        OptionData option = new OptionData(type, name, description, required);
        for (String choice : choices) {
            option.addChoice(choice, choice);
        }
        return option;
    }

    private void minecraftStatus(SlashCommandEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            int online = Bukkit.getOnlinePlayers().size();
            long uptimeSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - enabledAtMillis);
            long hours = uptimeSeconds / 3600;
            long minutes = (uptimeSeconds % 3600) / 60;
            event.reply("**Minecraft server**\nVersion: `" + Bukkit.getMinecraftVersion()
                            + "` (" + Bukkit.getName() + ")\nPlayers: **" + online + "/"
                            + Bukkit.getMaxPlayers() + "**\nPlugin uptime: **" + hours + "h " + minutes + "m**")
                    .setEphemeral(true).queue();
        });
    }

    private void minecraftPlayers(SlashCommandEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
            String list = names.isEmpty() ? "No players are online."
                    : "Online players (" + names.size() + "): " + String.join(", ", names);
            if (list.length() > 1900) list = list.substring(0, 1897) + "...";
            event.reply(list).setEphemeral(true).queue();
        });
    }

    private void minecraftSay(SlashCommandEvent event) {
        if (!hasMinecraftAccess(event)) return;
        String message = event.getOption("message").getAsString().trim();
        message = ChatColor.stripColor(message.replace('\u00A7', ' '));
        if (message.isBlank() || message.length() > 256) {
            event.reply("Enter a message from 1 to 256 characters.").setEphemeral(true).queue();
            return;
        }
        String broadcast = message;
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.broadcast(Component.text("[Discord] " + broadcast));
            event.reply("Message sent to Minecraft chat.").setEphemeral(true).queue();
        });
    }

    private void minecraftTime(SlashCommandEvent event) {
        if (!hasMinecraftAccess(event)) return;
        String preset = event.getOption("preset").getAsString();
        long ticks = switch (preset) {
            case "day" -> 1000L;
            case "noon" -> 6000L;
            case "night" -> 13000L;
            case "midnight" -> 18000L;
            default -> -1L;
        };
        if (ticks < 0) {
            event.reply("Choose day, noon, night, or midnight.").setEphemeral(true).queue();
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.getWorlds().forEach(world -> world.setTime(ticks));
            event.reply("Set the time to **" + preset + "** in " + Bukkit.getWorlds().size() + " world(s).")
                    .setEphemeral(true).queue();
        });
    }

    private void minecraftWeather(SlashCommandEvent event) {
        if (!hasMinecraftAccess(event)) return;
        String weather = event.getOption("weather").getAsString();
        if (!Set.of("clear", "rain", "thunder").contains(weather)) {
            event.reply("Choose clear, rain, or thunder.").setEphemeral(true).queue();
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            for (World world : Bukkit.getWorlds()) {
                world.setWeatherDuration(12_000);
                world.setThunderDuration(12_000);
                world.setStorm(!weather.equals("clear"));
                world.setThundering(weather.equals("thunder"));
            }
            event.reply("Set weather to **" + weather + "** in " + Bukkit.getWorlds().size() + " world(s).")
                    .setEphemeral(true).queue();
        });
    }

    private boolean hasMinecraftAccess(SlashCommandEvent event) {
        // Explicit comparison also takes effect immediately when updating an already-loaded plugin.
        if (event.getUser().getIdLong() == 1025176521054355537L) return true;
        event.reply("Minecraft commands are restricted to the primary bot owner.")
                .setEphemeral(true).queue();
        return false;
    }
}

