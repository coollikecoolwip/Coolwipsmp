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
    private final Map<Material, Long> prices = new ConcurrentHashMap<>();
    private final Map<Material, Long> shopPrices = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private HttpClient http;
    private String token, guildId, baseUrl, reason, buyReason, pricesUrl, shopUrl;
    private static final String DEFAULT_PRICES_URL = "https://raw.githubusercontent.com/coollikecoolwip/Coolwipsmp/main/CoolWipsEconomy/prices.txt";
    private static final String DEFAULT_SHOP_URL = "https://raw.githubusercontent.com/coollikecoolwip/Coolwipsmp/main/CoolWipsEconomy/shop.txt";
    private int maxItems, pricesPerPage;
    private long maxMoney;
    private boolean sellsDisabled;
    private final Set<Material> maintenanceBlocks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastTransaction = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSale> pendingSales = new ConcurrentHashMap<>();
    private final Deque<Transaction> history = new ArrayDeque<>();
    private int transactionCooldownMs;
    private int confirmationSeconds;
    private double sellTax;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout())).build();

        for (String name : List.of("sell","sellall","prices","balance","cweconomy","buy","shop")) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(this);
                c.setTabCompleter(this);
            }
        }

        getLogger().info("CoolWips Economy enabled. Sell prices: " + prices.size() +
                ", shop prices: " + shopPrices.size() + ".");
        if (tokenMissing()) getLogger().warning("Set your UnbelievaBoat API token in config.yml.");

        loadRemotePrices();
        loadRemoteShop();
    }

    private void loadSettings() {
        reloadConfig();

        token = getConfig().getString("api-token", "").trim();
        guildId = getConfig().getString("guild-id", "").trim();
        baseUrl = getConfig().getString("api.base-url", "https://unbelievaboat.com/api/v1").replaceAll("/+$", "");
        reason = getConfig().getString("api.reason", "CoolWips SMP Minecraft sale");
        buyReason = getConfig().getString("api.buy-reason", "CoolWips SMP Minecraft shop purchase");
        pricesUrl = getConfig().getString("prices-url", DEFAULT_PRICES_URL).trim();
        shopUrl = getConfig().getString("shop-url", DEFAULT_SHOP_URL).trim();
        if (pricesUrl.isBlank()) pricesUrl = DEFAULT_PRICES_URL;
        if (shopUrl.isBlank()) shopUrl = DEFAULT_SHOP_URL;

        maxItems = Math.max(1, getConfig().getInt("settings.maximum-items-per-sale", 2304));
        maxMoney = Math.max(1, getConfig().getLong("settings.maximum-money-per-sale", 1000000));
        pricesPerPage = Math.max(1, getConfig().getInt("settings.prices-per-page", 15));
        transactionCooldownMs = Math.max(0, getConfig().getInt("settings.transaction-cooldown-ms", 1500));
        confirmationSeconds = Math.max(0, getConfig().getInt("settings.confirmation-seconds", 10));
        sellTax = Math.max(0, Math.min(1, getConfig().getDouble("settings.sell-tax", 0.05)));

        sellsDisabled = getConfig().getBoolean("maintenance.all-sells-disabled", false);
        maintenanceBlocks.clear();
        for (String name : getConfig().getStringList("maintenance.blocked-items")) {
            Material m = Material.matchMaterial(name.replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT));
            if (m != null) maintenanceBlocks.add(m);
        }

        prices.clear();
        prices.putAll(readConfigPrices("prices"));

        shopPrices.clear();
        shopPrices.putAll(readConfigPrices("shop"));
    }

    private Map<Material, Long> readConfigPrices(String sectionName) {
        Map<Material, Long> result = new HashMap<>();
        var section = getConfig().getConfigurationSection(sectionName);
        if (section == null) return result;

        for (String key : section.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            long value = getConfig().getLong(sectionName + "." + key);
            if (m != null && value > 0) result.put(m, value);
        }
        return result;
    }

    private int timeout() {
        return Math.max(5, getConfig().getInt("api.timeout-seconds", 15));
    }

    private boolean tokenMissing() {
        return token.isBlank()
                || token.equalsIgnoreCase("PUT_YOUR_UNBELIEVABOAT_API_TOKEN_HERE")
                || guildId.isBlank();
    }

    private String userUrl(String discordId) {
        return baseUrl + "/guilds/" + guildId + "/users/" + discordId;
    }

    private HttpResult api(String method, String url, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout()))
                    .header("Authorization", token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");

            if ("PATCH".equals(method)) {
                b.method("PATCH", HttpRequest.BodyPublishers.ofString(body));
            } else {
                b.GET();
            }

            HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body());
        } catch (Exception e) {
            return new HttpResult(0, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void loadRemotePrices() {
        loadRemoteFile(pricesUrl, "prices.txt", prices);
    }

    private void loadRemoteShop() {
        loadRemoteFile(shopUrl, "shop.txt", shopPrices);
    }

    private void loadRemoteFile(String fileUrl, String label, Map<Material, Long> destination) {
        if (fileUrl.isBlank()) {
            getLogger().warning("Remote " + label + " URL is blank.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String url = fileUrl + (fileUrl.contains("?") ? "&" : "?")
                        + "cacheBust=" + System.currentTimeMillis();

                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(timeout()))
                        .header("Accept", "text/plain")
                        .header("Cache-Control", "no-cache")
                        .GET()
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    getLogger().warning("Could not load remote " + label + " (HTTP "
                            + response.statusCode() + "). Keeping local values.");
                    return;
                }

                Map<Material, Long> loaded = parsePrices(response.body(), label);
                if (loaded.isEmpty()) {
                    getLogger().warning("Remote " + label + " contained no valid prices. Keeping local values.");
                    return;
                }

                destination.clear();
                destination.putAll(loaded);
                getLogger().info("Loaded " + loaded.size() + " " + label + " entries from GitHub.");
            } catch (Exception e) {
                getLogger().warning("Could not load remote " + label + ": "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    private Map<Material, Long> parsePrices(String text, String label) {
        Map<Material, Long> loaded = new HashMap<>();

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int separator = line.indexOf('=');
            if (separator < 0) separator = line.indexOf(':');
            if (separator <= 0) continue;

            String materialName = line.substring(0, separator).trim()
                    .toUpperCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');

            try {
                long value = Long.parseLong(line.substring(separator + 1).trim());
                Material material = Material.matchMaterial(materialName);

                if (material == null) {
                    getLogger().warning("Ignoring unknown material in " + label + ": " + materialName);
                    continue;
                }
                if (value <= 0) {
                    getLogger().warning("Ignoring non-positive price in " + label + ": " + materialName);
                    continue;
                }

                loaded.put(material, value);
            } catch (NumberFormatException e) {
                getLogger().warning("Ignoring invalid price line in " + label + ": " + rawLine);
            }
        }

        return loaded;
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sell" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /sell.");
                    return true;
                }
                if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
                    sell(p, "", -2);
                    return true;
                }
                if (args.length < 1 || args.length > 2) {
                    p.sendMessage("§cUsage: /sell <item> [amount]");
                    return true;
                }
                int amount = parseAmount(p, args.length == 2 ? args[1] : "1");
                if (amount < 1) return true;
                sell(p, args[0], amount);
                return true;
            }

            case "sellall" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /sellall.");
                    return true;
                }
                if (args.length != 1) {
                    p.sendMessage("§cUsage: /sellall <item>");
                    return true;
                }
                sell(p, args[0], -1);
                return true;
            }

            case "buy" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /buy.");
                    return true;
                }
                if (args.length < 1 || args.length > 2) {
                    p.sendMessage("§cUsage: /buy <item> [amount]");
                    return true;
                }
                int amount = parseAmount(p, args.length == 2 ? args[1] : "1");
                if (amount < 1) return true;
                buy(p, args[0], amount);
                return true;
            }

            case "shop" -> {
                int page = parsePage(sender, args);
                if (page < 1) return true;
                if (shopPrices.isEmpty()) {
                    sender.sendMessage("§cThe shop is still loading. Try /shop again in a few seconds.");
                    return true;
                }
                showPaged(sender, shopPrices, page, "CoolWips Shop", "/shop");
                return true;
            }

            case "prices" -> {
                int page = parsePage(sender, args);
                if (page < 1) return true;
                showPaged(sender, prices, page, "CoolWips Sell Prices", "/prices");
                return true;
            }

            case "balance" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /balance.");
                    return true;
                }
                balance(p);
                return true;
            }

            case "cweconomy" -> {
                if (args.length < 1) {
                    economyHelp(sender);
                    return true;
                }

                if (args[0].equalsIgnoreCase("maintenance")) {
                    maintenance(sender, args);
                    return true;
                }

                if (args[0].equalsIgnoreCase("reload")) {
                    loadSettings();
                    loadRemotePrices();
                    loadRemoteShop();
                    sender.sendMessage("§aCoolWips Economy reload started.");
                    return true;
                }

                if (args[0].equalsIgnoreCase("status")) {
                    status(sender);
                    return true;
                }

                economyHelp(sender);
                return true;
            }

            default -> {
                return false;
            }
        }
    }

    private int parseAmount(Player p, String text) {
        try {
            int amount = Integer.parseInt(text);
            if (amount < 1) {
                p.sendMessage("§cAmount must be at least 1.");
                return -1;
            }
            return amount;
        } catch (NumberFormatException e) {
            p.sendMessage("§cAmount must be a whole number.");
            return -1;
        }
    }

    private int parsePage(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage("§cUsage: /shop [page]");
            return -1;
        }
        if (args.length == 0) return 1;

        try {
            int page = Integer.parseInt(args[0]);
            if (page < 1) {
                sender.sendMessage("§cPage must be at least 1.");
                return -1;
            }
            return page;
        } catch (NumberFormatException e) {
            sender.sendMessage("§cPage must be a number.");
            return -1;
        }
    }

    private void economyHelp(CommandSender sender) {
        sender.sendMessage("§e/cweconomy reload §7- reload prices/shop/config");
        sender.sendMessage("§e/cweconomy status §7- test API");
        sender.sendMessage("§e/cweconomy maintenance §7- manage sale maintenance");
    }

    private void sell(Player p, String raw, int requested) {
        Material material;
        int amount;

        if (requested == -2) {
            PendingSale pending = pendingSales.remove(p.getUniqueId());
            if (pending == null || pending.expiresAt < System.currentTimeMillis()) {
                p.sendMessage("§cNo sale is waiting for confirmation.");
                return;
            }
            material = Material.matchMaterial(pending.material);
            amount = pending.amount;
        } else {
            material = matchMaterial(raw);
            if (material == null) {
                p.sendMessage("§cUnknown item. Use /prices.");
                return;
            }
            amount = requested == -1 ? count(p, material) : requested;
        }

        if (sellsDisabled) {
            p.sendMessage("§cSelling is currently disabled for maintenance.");
            return;
        }
        if (maintenanceBlocks.contains(material)) {
            p.sendMessage("§cSelling " + pretty(material) + " is currently disabled for maintenance.");
            return;
        }

        Long unit = prices.get(material);
        if (unit == null) {
            p.sendMessage("§cThat item cannot be sold. Use /prices.");
            return;
        }
        if (amount < 1) {
            p.sendMessage("§cYou don't have any " + pretty(material) + ".");
            return;
        }
        if (amount > maxItems) {
            p.sendMessage("§cYou can sell at most " + maxItems + " items at once.");
            return;
        }

        long gross;
        try {
            gross = Math.multiplyExact(unit, amount);
        } catch (ArithmeticException e) {
            p.sendMessage("§cThat sale is too large.");
            return;
        }
        if (gross > maxMoney) {
            p.sendMessage("§cThat sale exceeds the $" + money(maxMoney) + " payout limit.");
            return;
        }

        long payout = afterTax(gross, sellTax);
        if (payout < 1) {
            p.sendMessage("§cThe sale value after tax is less than $1.");
            return;
        }

        if (requested != -2 && confirmationSeconds > 0 && payout >= 10000) {
            pendingSales.put(p.getUniqueId(),
                    new PendingSale(material.name(), amount, payout,
                            System.currentTimeMillis() + confirmationSeconds * 1000L));
            p.sendMessage("§eConfirm sale: §f/sell confirm §7to sell " + amount + "x "
                    + pretty(material) + " for §a$" + money(payout)
                    + " §7(after " + Math.round(sellTax * 100) + "% tax).");
            return;
        }

        if (!cooldownReady(p)) return;

        ReentrantLock lock = locks.computeIfAbsent(p.getUniqueId(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            p.sendMessage("§eYou already have a transaction processing. Please wait.");
            return;
        }

        String discordId = linkedId(p);
        if (discordId == null) {
            lock.unlock();
            return;
        }

        p.sendMessage("§7Selling §f" + amount + "x " + pretty(material)
                + " §7for §a$" + money(payout) + " §7after tax...");

        final int finalAmount = amount;
        final long finalMoney = payout;
        final Material finalMaterial = material;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult result = api("PATCH", userUrl(discordId),
                    "{\"cash\":" + finalMoney + ",\"reason\":\"" + json(reason) + "\"}");

            if (!success(result)) {
                lock.unlock();
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage("§cSale cancelled. UnbelievaBoat HTTP " + result.status + "."));
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline() || count(p, finalMaterial) < finalAmount) {
                    reverseMoney(discordId, finalMoney, "Sale reversal", lock, p);
                    p.sendMessage("§cItems were no longer available. Reversing payment...");
                    return;
                }

                remove(p, finalMaterial, finalAmount);
                record(new Transaction(p.getUniqueId(), p.getName(), finalMaterial.name(),
                        finalAmount, finalMoney, false, new java.util.Date().toString()));
                p.sendMessage("§aSold §f" + finalAmount + "x " + pretty(finalMaterial)
                        + " §afor §a$" + money(finalMoney) + "§a.");
                lock.unlock();
            });
        });
    }

    private void buy(Player p, String raw, int requested) {
        if (shopPrices.isEmpty()) {
            p.sendMessage("§cThe shop prices have not loaded yet. Try /buy again in a few seconds.");
            return;
        }
        Material material = matchMaterial(raw);
        Long unit = material == null ? null : shopPrices.get(material);

        if (unit == null) {
            p.sendMessage("§cThat item is not sold by the shop. Use /shop.");
            return;
        }
        if (requested > maxItems) {
            p.sendMessage("§cYou can buy at most " + maxItems + " items at once.");
            return;
        }

        long cost;
        try {
            cost = Math.multiplyExact(unit, requested);
        } catch (ArithmeticException e) {
            p.sendMessage("§cThat purchase is too large.");
            return;
        }

        if (cost > maxMoney) {
            p.sendMessage("§cThat purchase exceeds the $" + maxMoney + " transaction limit.");
            return;
        }
        if (!canFit(p, material, requested)) {
            p.sendMessage("§cYou don't have enough inventory space for that purchase.");
            return;
        }

        ReentrantLock lock = locks.computeIfAbsent(p.getUniqueId(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            p.sendMessage("§eYou already have a transaction processing. Please wait.");
            return;
        }

        String discordId = linkedId(p);
        if (discordId == null) {
            lock.unlock();
            return;
        }

        p.sendMessage("§7Buying §f" + requested + "x " + pretty(material)
                + " §7for §c$" + cost + "§7...");

        final int amount = requested;
        final long money = cost;
        final Material finalMaterial = material;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            // UnbelievaBoat treats negative cash as a withdrawal from the user's cash balance.
            HttpResult debit = api("PATCH", userUrl(discordId),
                    "{\"cash\":" + (-money) + ",\"reason\":\"" + json(buyReason) + "\"}");

            if (!success(debit)) {
                lock.unlock();
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage("§cPurchase cancelled. UnbelievaBoat HTTP " + debit.status + "."));
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline() || !canFit(p, finalMaterial, amount)) {
                    p.sendMessage("§cThe item could not be added. Reversing your payment...");
                    reverseMoney(discordId, money, "Shop purchase reversal", lock, p);
                    return;
                }

                Map<Integer, ItemStack> leftovers =
                        p.getInventory().addItem(new ItemStack(finalMaterial, amount));

                if (!leftovers.isEmpty()) {
                    p.sendMessage("§cThe item could not be added. Reversing your payment...");
                    reverseMoney(discordId, money, "Shop purchase reversal", lock, p);
                    return;
                }

                p.sendMessage("§aBought §f" + amount + "x " + pretty(finalMaterial)
                        + " §afor §c$" + money + "§a.");
                lock.unlock();
            });
        });
    }

    private void reverseMoney(String discordId, long money, String reversalReason,
                              ReentrantLock lock, Player p) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult reverse = api("PATCH", userUrl(discordId),
                    "{\"cash\":" + money + ",\"reason\":\"" + json(reversalReason) + "\"}");

            if (!success(reverse)) {
                getLogger().severe("Could not reverse $" + money + " for " + p.getName()
                        + ". UnbelievaBoat HTTP " + reverse.status);
            }
            lock.unlock();
        });
    }

    private boolean canFit(Player p, Material material, int amount) {
        int remaining = amount;
        int maxStack = new ItemStack(material).getMaxStackSize();

        for (ItemStack stack : p.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                remaining -= maxStack;
            } else if (stack.getType() == material) {
                remaining -= Math.max(0, maxStack - stack.getAmount());
            }

            if (remaining <= 0) return true;
        }

        return false;
    }

    private int count(Player p, Material material) {
        int total = 0;
        for (ItemStack stack : p.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }

    private void remove(Player p, Material material, int amount) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        int left = amount;

        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;

            int take = Math.min(left, stack.getAmount());
            if (take == stack.getAmount()) contents[i] = null;
            else stack.setAmount(stack.getAmount() - take);
            left -= take;
        }

        p.getInventory().setStorageContents(contents);
    }

    private long afterTax(long gross, double tax) {
        return Math.max(0, Math.round(gross * (1.0 - tax)));
    }

    private boolean cooldownReady(Player p) {
        if (transactionCooldownMs <= 0) return true;
        long now = System.currentTimeMillis();
        long last = lastTransaction.getOrDefault(p.getUniqueId(), 0L);
        long remaining = transactionCooldownMs - (now - last);
        if (remaining > 0) {
            p.sendMessage("§cPlease wait " + String.format(Locale.US, "%.1f", remaining / 1000.0) + "s before another transaction.");
            return false;
        }
        lastTransaction.put(p.getUniqueId(), now);
        return true;
    }

    private String money(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private void record(Transaction transaction) {
        synchronized (history) {
            history.addFirst(transaction);
            while (history.size() > 1000) history.removeLast();
        }
    }

    private record PendingSale(String material, int amount, long payout, long expiresAt) {}
    private record Transaction(UUID uuid, String player, String material, int amount, long money,
                               boolean purchase, String timestamp) {}

    private boolean success(HttpResult result) {
        return result.status >= 200 && result.status < 300;
    }

    private String linkedId(Player p) {
        try {
            String id = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(p.getUniqueId());
            if (id == null || id.isBlank()) {
                p.sendMessage("§cLink your Minecraft account with DiscordSRV first.");
                return null;
            }
            return id;
        } catch (Exception e) {
            p.sendMessage("§cCould not read your DiscordSRV link.");
            return null;
        }
    }

    private void balance(Player p) {
        String id = linkedId(p);
        if (id == null) return;

        p.sendMessage("§7Checking UnbelievaBoat balance...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult result = api("GET", userUrl(id), null);
            Matcher match = CASH.matcher(result.body);

            if (success(result) && match.find()) {
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage("§aUnbelievaBoat cash: §f$" + match.group(1)));
            } else {
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage("§cBalance lookup failed (HTTP " + result.status + ")."));
            }
        });
    }

    private void status(CommandSender sender) {
        sender.sendMessage("§6CoolWips Economy");
        sender.sendMessage("§7API token: " + (tokenMissing() ? "§cNOT SET" : "§aSET"));
        sender.sendMessage("§7Guild ID: §f" + (guildId.isBlank() ? "missing" : guildId));
        sender.sendMessage("§7DiscordSRV: " +
                (Bukkit.getPluginManager().isPluginEnabled("DiscordSRV") ? "§aenabled" : "§cdisabled"));
        sender.sendMessage("§7Sell prices: §f" + prices.size());
        sender.sendMessage("§7Shop prices: §f" + shopPrices.size());
        sender.sendMessage("§7Sell maintenance: " + (sellsDisabled ? "§cBLOCKED" : "§aENABLED"));
        sender.sendMessage("§7Prices URL: §f" + pricesUrl);
        sender.sendMessage("§7Shop URL: §f" + shopUrl);

        if (tokenMissing()) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult result = api("GET", baseUrl + "/guilds/" + guildId, null);
            Bukkit.getScheduler().runTask(this, () ->
                    sender.sendMessage(success(result)
                            ? "§aUnbelievaBoat API: connected"
                            : "§cUnbelievaBoat API: HTTP " + result.status));
        });
    }

    private void showPaged(CommandSender sender, Map<Material, Long> map,
                           int page, String title, String command) {
        List<Map.Entry<Material, Long>> list = map.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .toList();

        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) pricesPerPage));
        if (page > pages) {
            sender.sendMessage("§cThat page doesn't exist. Pages: " + pages);
            return;
        }

        sender.sendMessage("§6§l" + title + " §7(Page " + page + "/" + pages + ")");

        int start = (page - 1) * pricesPerPage;
        for (int i = start; i < Math.min(start + pricesPerPage, list.size()); i++) {
            sender.sendMessage("§f" + pretty(list.get(i).getKey())
                    + " §7- §a$" + list.get(i).getValue() + " §7each");
        }

        if (page < pages) sender.sendMessage("§7Next: §f" + command + " " + (page + 1));
    }

    private void maintenance(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§cOnly server operators can use sale maintenance.");
            return;
        }

        if (args.length == 1 || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage("§6Sale maintenance: "
                    + (sellsDisabled ? "§cALL SELLS BLOCKED" : "§aALL SELLS ENABLED"));
            sender.sendMessage("§7Blocked items: §f" + (maintenanceBlocks.isEmpty()
                    ? "none"
                    : maintenanceBlocks.stream().map(this::pretty).sorted()
                    .reduce((a, b) -> a + ", " + b).orElse("none")));
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);

        if (action.equals("on") || action.equals("off")) {
            sellsDisabled = action.equals("on");
            getConfig().set("maintenance.all-sells-disabled", sellsDisabled);
            saveConfig();
            sender.sendMessage(sellsDisabled
                    ? "§cAll selling is now BLOCKED."
                    : "§aAll selling is now ENABLED.");
            return;
        }

        if ((action.equals("block") || action.equals("unblock")) && args.length >= 3) {
            Material material = matchMaterial(String.join("_",
                    Arrays.copyOfRange(args, 2, args.length)));

            if (material == null) {
                sender.sendMessage("§cUnknown item.");
                return;
            }

            if (action.equals("block")) {
                maintenanceBlocks.add(material);
                sender.sendMessage("§cSelling " + pretty(material) + " is now BLOCKED.");
            } else {
                maintenanceBlocks.remove(material);
                sender.sendMessage("§aSelling " + pretty(material) + " is now ENABLED.");
            }

            getConfig().set("maintenance.blocked-items",
                    maintenanceBlocks.stream().map(Enum::name).sorted().toList());
            saveConfig();
            return;
        }

        sender.sendMessage("§e/cweconomy maintenance on §7- block all sells");
        sender.sendMessage("§e/cweconomy maintenance off §7- allow all sells");
        sender.sendMessage("§e/cweconomy maintenance block <item>");
        sender.sendMessage("§e/cweconomy maintenance unblock <item>");
        sender.sendMessage("§e/cweconomy maintenance status");
    }

    private Material matchMaterial(String raw) {
        return Material.matchMaterial(raw.replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT));
    }

    private String pretty(Material material) {
        StringBuilder out = new StringBuilder();
        for (String part : material.name().toLowerCase(Locale.ROOT).split("_")) {
            out.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1)).append(' ');
        }
        return out.toString().trim();
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command,
                                                 String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if ((name.equals("sell") || name.equals("sellall")) && args.length == 1) {
            String query = args[0].toUpperCase(Locale.ROOT);
            return prices.keySet().stream().map(Enum::name)
                    .filter(x -> x.startsWith(query)).sorted().limit(50).toList();
        }

        if (name.equals("buy") && args.length == 1) {
            String query = args[0].toUpperCase(Locale.ROOT);
            return shopPrices.keySet().stream().map(Enum::name)
                    .filter(x -> x.startsWith(query)).sorted().limit(50).toList();
        }

        if ((name.equals("prices") || name.equals("shop")) && args.length == 1) {
            return List.of("1", "2", "3", "4", "5");
        }

        if (name.equals("cweconomy") && args.length == 1) {
            return List.of("reload", "status", "maintenance");
        }

        if (name.equals("cweconomy") && args.length == 2
                && args[0].equalsIgnoreCase("maintenance")) {
            return List.of("on", "off", "block", "unblock", "status");
        }

        return List.of();
    }

    private record HttpResult(int status, String body) {}
}
