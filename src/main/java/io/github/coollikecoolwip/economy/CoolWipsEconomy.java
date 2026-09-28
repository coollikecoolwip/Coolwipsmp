package io.github.coollikecoolwip.economy;

import github.scarsz.discordsrv.DiscordSRV;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CoolWipsEconomy extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final Pattern CASH = Pattern.compile("\"cash\"\\s*:\\s*(-?\\d+)");
    private final Map<Material, Long> prices = new HashMap<>();
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private HttpClient http;
    private String token, guildId, baseUrl, reason;
    private int maxItems, pricesPerPage;
    private long maxMoney;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout())).build();
        for (String name : List.of("sell","sellall","prices","balance","cweconomy")) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        }
        getLogger().info("CoolWips Economy enabled. " + prices.size() + " prices loaded.");
        if (tokenMissing()) getLogger().warning("Set your UnbelievaBoat API token in config.yml.");
    }

    private void loadSettings() {
        reloadConfig();
        token = getConfig().getString("api-token", "").trim();
        guildId = getConfig().getString("guild-id", "").trim();
        baseUrl = getConfig().getString("api.base-url", "https://unbelievaboat.com/api/v1").replaceAll("/+$", "");
        reason = getConfig().getString("api.reason", "CoolWips SMP Minecraft sale");
        maxItems = Math.max(1, getConfig().getInt("settings.maximum-items-per-sale", 2304));
        maxMoney = Math.max(1, getConfig().getLong("settings.maximum-money-per-sale", 1000000));
        pricesPerPage = Math.max(1, getConfig().getInt("settings.prices-per-page", 15));
        prices.clear();
        var section = getConfig().getConfigurationSection("prices");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            long p = getConfig().getLong("prices." + key);
            if (m != null && p >= 0) prices.put(m, p);
        }
    }

    private int timeout() { return Math.max(5, getConfig().getInt("api.timeout-seconds", 15)); }
    private boolean tokenMissing() {
        return token.isBlank() || token.equalsIgnoreCase("PUT_YOUR_UNBELIEVABOAT_API_TOKEN_HERE") || guildId.isBlank();
    }
    private String userUrl(String discordId) { return baseUrl + "/guilds/" + guildId + "/users/" + discordId; }

    private HttpResult api(String method, String url, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout()))
                    .header("Authorization", token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if ("PATCH".equals(method)) b.method("PATCH", HttpRequest.BodyPublishers.ofString(body));
            else b.GET();
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(r.statusCode(), r.body());
        } catch (Exception e) {
            return new HttpResult(0, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private String json(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sell" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /sell."); return true; }
                if (args.length < 1 || args.length > 2) { p.sendMessage("§cUsage: /sell <item> [amount]"); return true; }
                int amount = 1;
                if (args.length == 2) try { amount = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
                    p.sendMessage("§cAmount must be a whole number."); return true;
                }
                sell(p, args[0], amount); return true;
            }
            case "sellall" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /sellall."); return true; }
                if (args.length != 1) { p.sendMessage("§cUsage: /sellall <item>"); return true; }
                sell(p, args[0], -1); return true;
            }
            case "prices" -> {
                int page = 1;
                if (args.length > 1) { sender.sendMessage("§cUsage: /prices [page]"); return true; }
                if (args.length == 1) try { page = Math.max(1, Integer.parseInt(args[0])); } catch (NumberFormatException e) {
                    sender.sendMessage("§cPage must be a number."); return true;
                }
                showPrices(sender, page); return true;
            }
            case "balance" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /balance."); return true; }
                balance(p); return true;
            }
            case "cweconomy" -> {
                if (args.length != 1 || (!args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("status"))) {
                    sender.sendMessage("§e/cweconomy reload §7- reload config");
                    sender.sendMessage("§e/cweconomy status §7- test API");
                    return true;
                }
                if (args[0].equalsIgnoreCase("reload")) { loadSettings(); sender.sendMessage("§aCoolWips Economy reloaded."); }
                else status(sender);
                return true;
            }
            default -> { return false; }
        }
    }

    private void sell(Player p, String raw, int requested) {
        Material m = Material.matchMaterial(raw.replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT));
        Long unit = m == null ? null : prices.get(m);
        if (unit == null) { p.sendMessage("§cThat item cannot be sold. Use /prices."); return; }
        if (unit <= 0) { p.sendMessage("§cThat item has no sell value."); return; }
        int amount = requested == -1 ? count(p, m) : requested;
        if (amount < 1) { p.sendMessage("§cYou don't have any " + pretty(m) + "."); return; }
        if (amount > maxItems) { p.sendMessage("§cYou can sell at most " + maxItems + " items at once."); return; }
        long payout;
        try { payout = Math.multiplyExact(unit, amount); } catch (ArithmeticException e) { p.sendMessage("§cThat sale is too large."); return; }
        if (payout > maxMoney) { p.sendMessage("§cThat sale exceeds the $" + maxMoney + " payout limit."); return; }

        ReentrantLock lock = locks.computeIfAbsent(p.getUniqueId(), k -> new ReentrantLock());
        if (!lock.tryLock()) { p.sendMessage("§eYou already have a sale processing. Please wait."); return; }

        String discordId;
        try { discordId = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(p.getUniqueId()); }
        catch (Exception e) { lock.unlock(); p.sendMessage("§cCould not read your DiscordSRV link."); return; }
        if (discordId == null || discordId.isBlank()) {
            lock.unlock();
            p.sendMessage("§cLink your Minecraft account with DiscordSRV first.");
            return;
        }

        p.sendMessage("§7Selling §f" + amount + "x " + pretty(m) + " §7for §a$" + payout + "§7...");
        final int a = amount; final long money = payout; final Material material = m;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult r = api("PATCH", userUrl(discordId),
                    "{\"cash\":" + money + ",\"reason\":\"" + json(reason) + "\"}");
            if (r.status < 200 || r.status >= 300) {
                lock.unlock();
                Bukkit.getScheduler().runTask(this, () -> p.sendMessage("§cSale cancelled. UnbelievaBoat HTTP " + r.status + "."));
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline() || count(p, material) < a) {
                    p.sendMessage("§cItems were no longer available. Reversing payment...");
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        HttpResult reverse = api("PATCH", userUrl(discordId),
                                "{\"cash\":" + (-money) + ",\"reason\":\"Sale reversal\"}");
                        if (reverse.status < 200 || reverse.status >= 300)
                            getLogger().severe("Could not reverse a failed item removal for " + p.getName() + ".");
                        lock.unlock();
                    });
                    return;
                }
                remove(p, material, a);
                p.sendMessage("§aSold §f" + a + "x " + pretty(material) + " §afor §a$" + money + "§a.");
                lock.unlock();
            });
        });
    }

    private int count(Player p, Material m) {
        int total = 0;
        for (ItemStack s : p.getInventory().getStorageContents())
            if (s != null && s.getType() == m) total += s.getAmount();
        return total;
    }

    private void remove(Player p, Material m, int amount) {
        ItemStack[] c = p.getInventory().getStorageContents();
        int left = amount;
        for (int i = 0; i < c.length && left > 0; i++) {
            ItemStack s = c[i];
            if (s == null || s.getType() != m) continue;
            int take = Math.min(left, s.getAmount());
            if (take == s.getAmount()) c[i] = null; else s.setAmount(s.getAmount() - take);
            left -= take;
        }
        p.getInventory().setStorageContents(c);
    }

    private void balance(Player p) {
        String id = linkedId(p);
        if (id == null) return;
        p.sendMessage("§7Checking UnbelievaBoat balance...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult r = api("GET", userUrl(id), null);
            Matcher match = CASH.matcher(r.body);
            if (r.status >= 200 && r.status < 300 && match.find())
                Bukkit.getScheduler().runTask(this, () -> p.sendMessage("§aUnbelievaBoat cash: §f$" + match.group(1)));
            else Bukkit.getScheduler().runTask(this, () -> p.sendMessage("§cBalance lookup failed (HTTP " + r.status + ")."));
        });
    }

    private String linkedId(Player p) {
        try {
            String id = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(p.getUniqueId());
            if (id == null || id.isBlank()) { p.sendMessage("§cLink your Minecraft account with DiscordSRV first."); return null; }
            return id;
        } catch (Exception e) { p.sendMessage("§cCould not read your DiscordSRV link."); return null; }
    }

    private void status(CommandSender s) {
        s.sendMessage("§6CoolWips Economy");
        s.sendMessage("§7API token: " + (tokenMissing() ? "§cNOT SET" : "§aSET"));
        s.sendMessage("§7Guild ID: §f" + (guildId.isBlank() ? "missing" : guildId));
        s.sendMessage("§7DiscordSRV: " + (Bukkit.getPluginManager().isPluginEnabled("DiscordSRV") ? "§aenabled" : "§cdisabled"));
        if (tokenMissing()) return;
        s.sendMessage("§7Testing UnbelievaBoat...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult r = api("GET", baseUrl + "/guilds/" + guildId, null);
            Bukkit.getScheduler().runTask(this, () -> s.sendMessage(r.status >= 200 && r.status < 300
                    ? "§aUnbelievaBoat API: connected" : "§cUnbelievaBoat API: HTTP " + r.status));
        });
    }

    private void showPrices(CommandSender s, int page) {
        List<Map.Entry<Material, Long>> list = prices.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name())).toList();
        int pages = Math.max(1, (int)Math.ceil(list.size() / (double)pricesPerPage));
        if (page > pages) { s.sendMessage("§cThat page doesn't exist. Pages: " + pages); return; }
        s.sendMessage("§6§lCoolWips Economy Prices §7(Page " + page + "/" + pages + ")");
        int start = (page - 1) * pricesPerPage;
        for (int i = start; i < Math.min(start + pricesPerPage, list.size()); i++)
            s.sendMessage("§f" + pretty(list.get(i).getKey()) + " §7- §a$" + list.get(i).getValue() + " §7each");
        if (page < pages) s.sendMessage("§7Next: §f/prices " + (page + 1));
    }

    private String pretty(Material m) {
        StringBuilder out = new StringBuilder();
        for (String part : m.name().toLowerCase(Locale.ROOT).split("_"))
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        return out.toString().trim();
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        String n = c.getName().toLowerCase(Locale.ROOT);
        if ((n.equals("sell") || n.equals("sellall")) && args.length == 1) {
            String q = args[0].toUpperCase(Locale.ROOT);
            return prices.keySet().stream().map(Enum::name).filter(x -> x.startsWith(q)).sorted().limit(50).toList();
        }
        if (n.equals("prices") && args.length == 1) return List.of("1","2","3","4","5");
        if (n.equals("cweconomy") && args.length == 1) return List.of("reload","status");
        return List.of();
    }

    private record HttpResult(int status, String body) {}
}
