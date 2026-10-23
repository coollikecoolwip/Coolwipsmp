package local.coolwips.chatbridge;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.dependencies.jda.api.events.message.MessageReceivedEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import github.scarsz.discordsrv.dependencies.jda.api.requests.GatewayIntent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;

public final class CoolWipsChatBridge extends JavaPlugin implements Listener {
    private static final int DISCORD_MESSAGE_LIMIT = 2_000;
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final ListenerAdapter discordListener = new ListenerAdapter() {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (!getConfig().getBoolean("discord-to-minecraft", true)) return;
            if (!event.isFromGuild() || !event.getChannel().getId().equals(channelId())) return;
            if (event.getAuthor().isBot() || event.getMessage().isWebhookMessage()) return;

            String message = event.getMessage().getContentDisplay().trim();
            if (message.isEmpty() || isIgnoredDiscordMessage(message)) return;

            String safeName = sanitizeForMinecraft(event.getAuthor().getName());
            String safeMessage = sanitizeForMinecraft(message);
            String formatted = getConfig().getString("discord-message-format", "[Discord] %name%: %message%")
                    .replace("%name%", safeName)
                    .replace("%message%", safeMessage);
            Bukkit.getScheduler().runTask(CoolWipsChatBridge.this, () -> Bukkit.broadcastMessage(formatted));
        }
    };

    private JDA jda;
    private BukkitTask connectionTask;
    private boolean warnedMissingChannel;

    @Override
    public void onLoad() {
        // Declare the guild-message gateway intent before DiscordSRV constructs its JDA client.
        DiscordSRV.api.requireIntent(GatewayIntent.GUILD_MESSAGES);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        connectionTask = getServer().getScheduler().runTaskTimer(this, this::connectToDiscordSRV, 1L, 20L);
        getLogger().info("Chat-only bridge enabled; waiting for DiscordSRV to connect.");
    }

    @Override
    public void onDisable() {
        if (connectionTask != null) connectionTask.cancel();
        if (jda != null) {
            jda.removeEventListener(discordListener);
            jda = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinecraftChat(AsyncChatEvent event) {
        if (!getConfig().getBoolean("minecraft-to-discord", true)) return;

        String content = PLAIN_TEXT.serialize(event.message()).trim();
        if (content.isEmpty()) return;

        TextChannel channel = currentChannel();
        if (channel == null) return;

        String formatted = getConfig().getString("minecraft-message-format", "**%player%**: %message%")
                .replace("%player%", event.getPlayer().getName())
                .replace("%message%", content);
        formatted = escapeDiscordMentions(formatted);
        if (formatted.length() > DISCORD_MESSAGE_LIMIT) {
            formatted = formatted.substring(0, DISCORD_MESSAGE_LIMIT - 1) + "…";
        }
        channel.sendMessage(formatted).queue(
                ignored -> { },
                error -> getLogger().warning("Could not relay Minecraft chat to Discord: " + error.getMessage()));
    }

    private void connectToDiscordSRV() {
        if (jda != null) return;
        DiscordSRV discordSrv = DiscordSRV.getPlugin();
        JDA candidate = discordSrv == null ? null : discordSrv.getJda();
        if (candidate == null) return;

        TextChannel channel = candidate.getTextChannelById(channelId());
        if (channel == null) {
            if (!warnedMissingChannel) {
                getLogger().warning("Discord channel " + channelId()
                        + " is unavailable to the DiscordSRV bot; check its access and channel ID.");
                warnedMissingChannel = true;
            }
            return;
        }

        candidate.addEventListener(discordListener);
        jda = candidate;
        warnedMissingChannel = false;
        connectionTask.cancel();
        connectionTask = null;
        getLogger().info("Relaying player chat both ways through Discord channel " + channelId() + ".");
    }

    private TextChannel currentChannel() {
        return jda == null ? null : jda.getTextChannelById(channelId());
    }

    private String channelId() {
        return getConfig().getString("discord-channel-id", "").trim();
    }

    private boolean isIgnoredDiscordMessage(String message) {
        String normalized = message.stripLeading().toLowerCase(Locale.ROOT);
        List<String> prefixes = getConfig().getStringList("ignored-discord-prefixes");
        return prefixes.stream().filter(prefix -> !prefix.isEmpty())
                .map(prefix -> prefix.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::startsWith);
    }

    private static String sanitizeForMinecraft(String input) {
        return input.replace('\u00A7', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String escapeDiscordMentions(String input) {
        return input.replace("@", "@\u200B");
    }
}

