package io.github.coollikecoolwip.economy;

import github.scarsz.discordsrv.DiscordSRV;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.event.block.Action;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CoolWipsEconomy extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
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
    private final Map<UUID, PendingTrade> pendingTrades = new ConcurrentHashMap<>();
    private final Deque<Transaction> history = new ArrayDeque<>();
    private int transactionCooldownMs;
    private int confirmationSeconds;
    private double sellTax, buyTax;
    private NamespacedKey sellChestOwnerKey;
    private final Map<String, ReentrantLock> sellChestLocks = new ConcurrentHashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout())).build();
        sellChestOwnerKey = new NamespacedKey(this, "sell-chest-owner");
        Bukkit.getPluginManager().registerEvents(this, this);

        for (String name : List.of("sell","sellall","prices","balance","cweconomy","buy","shop","pay","sellto","buyfrom","sellchest","history")) {
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
        buyTax = Math.max(0, Math.min(1, getConfig().getDouble("settings.buy-tax", 0.05)));

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

            case "pay" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /pay.");
                    return true;
                }
                if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
                    payConfirm(p);
                    return true;
                }
                if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
                    pendingTrades.remove(p.getUniqueId());
                    p.sendMessage("§7Pending item trade cancelled.");
                    return true;
                }
                if (args.length < 2 || args.length > 3) {
                    p.sendMessage("§cUsage: /pay <player> <item> [amount]");
                    p.sendMessage("§7Example: /pay Steve diamond 5");
                    return true;
                }
                int amount = parseAmount(p, args.length == 3 ? args[2] : "1");
                if (amount < 1) return true;
                createTrade(p, args[0], args[1], amount);
                return true;
            }

            case "sellto" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /sellto.");
                    return true;
                }
                if (args.length < 2 || args.length > 3) {
                    p.sendMessage("§cUsage: /sellto <player> <item> [amount]");
                    p.sendMessage("§7The other player will be asked to confirm the purchase.");
                    return true;
                }
                int amount = parseAmount(p, args.length == 3 ? args[2] : "1");
                if (amount < 1) return true;

                Player buyer = Bukkit.getPlayerExact(args[0]);
                if (buyer == null) {
                    p.sendMessage("§cThat player must be online.");
                    return true;
                }
                if (buyer.getUniqueId().equals(p.getUniqueId())) {
                    p.sendMessage("§cYou cannot sell items to yourself.");
                    return true;
                }

                createTrade(buyer, p.getName(), args[1], amount);
                p.sendMessage("§aSale offer sent to §f" + buyer.getName() + "§a.");
                return true;
            }

            case "buyfrom" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /buyfrom.");
                    return true;
                }
                if (args.length < 2 || args.length > 3) {
                    p.sendMessage("§cUsage: /buyfrom <player> <item> [amount]");
                    p.sendMessage("§7The other player will be asked to confirm the sale.");
                    return true;
                }
                int amount = parseAmount(p, args.length == 3 ? args[2] : "1");
                if (amount < 1) return true;
                createTrade(p, args[0], args[1], amount);
                return true;
            }

            case "sellchest" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /sellchest.");
                    return true;
                }
                if (args.length != 1 || (!args[0].equalsIgnoreCase("create")
                        && !args[0].equalsIgnoreCase("remove")
                        && !args[0].equalsIgnoreCase("status"))) {
                    p.sendMessage("§e/sellchest create §7- make the chest you are looking at a sell chest");
                    p.sendMessage("§e/sellchest remove §7- remove sell-chest status");
                    p.sendMessage("§e/sellchest status §7- check the chest you are looking at");
                    return true;
                }

                Block target = p.getTargetBlockExact(6);
                if (target == null || !isChestBlock(target)) {
                    p.sendMessage("§cLook directly at a chest within 6 blocks.");
                    return true;
                }

                if (args[0].equalsIgnoreCase("create")) {
                    if (sellChestOwner(target) != null || (adjacentChest(target) != null
                            && sellChestOwner(adjacentChest(target)) != null)) {
                        p.sendMessage("§cThat chest is already part of a sell chest.");
                        return true;
                    }
                    markSellChest(target, p.getUniqueId());
                    p.sendMessage("§aSell chest created. Put sellable items inside and close the chest to sell them.");
                    return true;
                }

                UUID owner = sellChestOwner(target);
                if (owner == null) {
                    p.sendMessage("§7That chest is not a sell chest.");
                    return true;
                }

                if (args[0].equalsIgnoreCase("status")) {
                    p.sendMessage(owner.equals(p.getUniqueId())
                            ? "§aThis is your sell chest."
                            : "§cThis is another player's sell chest.");
                    return true;
                }

                if (!owner.equals(p.getUniqueId()) && !p.isOp()) {
                    p.sendMessage("§cOnly the owner can remove this sell chest.");
                    return true;
                }
                unmarkSellChest(target);
                p.sendMessage("§aSell chest removed. The items inside were not changed.");
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

            case "history" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /history.");
                    return true;
                }
                showHistory(p);
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

    @EventHandler
    public void onSellChestOpen(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !isChestBlock(block)) return;

        UUID owner = sellChestOwner(block);
        if (owner == null) return;

        Player player = event.getPlayer();
        if (!owner.equals(player.getUniqueId()) && !player.isOp()) {
            event.setCancelled(true);
            player.sendMessage("§cThat is not your sell chest.");
            return;
        }

        ReentrantLock lock = sellChestLocks.get(chestKey(block));
        if (lock != null && lock.isLocked()) {
            event.setCancelled(true);
            player.sendMessage("§eThat sell chest is processing a sale. Please wait.");
        }
    }

    @EventHandler
    public void onSellChestBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isChestBlock(block)) return;

        UUID owner = sellChestOwner(block);
        if (owner == null) return;

        Player player = event.getPlayer();
        if (!owner.equals(player.getUniqueId()) && !player.isOp()) {
            event.setCancelled(true);
            player.sendMessage("§cOnly the owner can break this sell chest.");
            return;
        }

        unmarkSellChest(block);
    }

    @EventHandler
    public void onSellChestClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        SellChestInfo info = getSellChestInfo(event.getInventory());
        if (info == null || !info.owner().equals(player.getUniqueId())) return;

        ReentrantLock lock = sellChestLocks.computeIfAbsent(info.key(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            player.sendMessage("§eThis sell chest is already processing.");
            return;
        }

        if (sellsDisabled) {
            lock.unlock();
            player.sendMessage("§cSelling is currently disabled for maintenance.");
            return;
        }

        Inventory inventory = event.getInventory();
        Map<Material, Integer> amounts = new LinkedHashMap<>();
        int totalItems = 0;
        long gross = 0;

        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            Material material = stack.getType();
            Long unit = prices.get(material);
            if (unit == null || maintenanceBlocks.contains(material)) continue;

            int amount = stack.getAmount();
            if (totalItems > maxItems - amount) {
                lock.unlock();
                player.sendMessage("§cThis sell chest contains more than " + maxItems
                        + " sellable items. Remove some items and close it again.");
                return;
            }

            long value;
            try {
                value = Math.multiplyExact(unit, amount);
                gross = Math.addExact(gross, value);
            } catch (ArithmeticException e) {
                lock.unlock();
                player.sendMessage("§cThe sell chest value is too large.");
                return;
            }

            totalItems += amount;
            amounts.merge(material, amount, Integer::sum);
        }

        if (amounts.isEmpty()) {
            lock.unlock();
            return;
        }

        if (gross > maxMoney) {
            lock.unlock();
            player.sendMessage("§cThis sell chest exceeds the $" + money(maxMoney)
                    + " payout limit. Remove some items.");
            return;
        }

        long payout = afterTax(gross, sellTax);
        if (payout < 1) {
            lock.unlock();
            player.sendMessage("§cThe sell chest value after tax is less than $1.");
            return;
        }

        List<ItemStack> removed = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();

        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) continue;
            if (!amounts.containsKey(stack.getType())) continue;

            removed.add(stack.clone());
            contents[i] = null;
        }
        inventory.setContents(contents);

        String discordId = linkedId(player);
        if (discordId == null) {
            restoreChestItems(inventory, removed);
            lock.unlock();
            return;
        }

        final long finalPayout = payout;
        final int finalTotalItems = totalItems;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult result = api("PATCH", userUrl(discordId),
                    "{\"cash\":" + finalPayout + ",\"reason\":\""
                            + json("CoolWips SMP sell chest") + "\"}");

            if (!success(result)) {
                Bukkit.getScheduler().runTask(this, () -> {
                    restoreChestItems(inventory, removed);
                    player.sendMessage("§cSell chest payout failed. Your items were returned. UnbelievaBoat HTTP "
                            + result.status + ".");
                    lock.unlock();
                });
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                for (Map.Entry<Material, Integer> entry : amounts.entrySet()) {
                    long itemGross = (long) entry.getValue() * prices.get(entry.getKey());
                    long itemPayout = afterTax(itemGross, sellTax);
                    record(new Transaction(player.getUniqueId(), player.getName(), entry.getKey().name(),
                            entry.getValue(), itemPayout, false, new java.util.Date().toString()));
                }

                player.sendMessage("§aSell chest sold §f" + finalTotalItems + " items §afor §a$"
                        + money(finalPayout) + "§a.");
                lock.unlock();
            });
        });
    }

    @EventHandler
    public void onSellChestClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (getSellChestInfo(inventory) == null) return;
        Bukkit.getScheduler().runTask(this, () -> processAutomaticSellChest(inventory));
    }

    @EventHandler
    public void onSellChestDrag(InventoryDragEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (getSellChestInfo(inventory) == null) return;
        Bukkit.getScheduler().runTask(this, () -> processAutomaticSellChest(inventory));
    }

    @EventHandler
    public void onSellChestMove(InventoryMoveItemEvent event) {
        Inventory destination = event.getDestination();
        if (getSellChestInfo(destination) == null) return;
        Bukkit.getScheduler().runTask(this, () -> processAutomaticSellChest(destination));
    }

    private void processAutomaticSellChest(Inventory inventory) {
        SellChestInfo info = getSellChestInfo(inventory);
        if (info == null || sellsDisabled) return;

        ReentrantLock lock = sellChestLocks.computeIfAbsent(info.key(), k -> new ReentrantLock());
        if (!lock.tryLock()) return;

        Map<Material, Integer> amounts = new LinkedHashMap<>();
        int totalItems = 0;
        long gross = 0;

        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            Long unit = prices.get(stack.getType());
            if (unit == null || maintenanceBlocks.contains(stack.getType())) continue;
            int amount = stack.getAmount();
            if (totalItems > maxItems - amount) {
                lock.unlock();
                return;
            }
            try {
                gross = Math.addExact(gross, Math.multiplyExact(unit, amount));
            } catch (ArithmeticException e) {
                lock.unlock();
                return;
            }
            totalItems += amount;
            amounts.merge(stack.getType(), amount, Integer::sum);
        }

        if (amounts.isEmpty() || gross > maxMoney) {
            lock.unlock();
            return;
        }

        long payout = afterTax(gross, sellTax);
        if (payout < 1) {
            lock.unlock();
            return;
        }

        List<ItemStack> removed = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir() || !amounts.containsKey(stack.getType())) continue;
            removed.add(stack.clone());
            contents[i] = null;
        }
        inventory.setContents(contents);

        String discordId = Bukkit.getOfflinePlayer(info.owner()).getName() == null ? null : linkedId(info.owner());
        if (discordId == null) {
            restoreChestItems(inventory, removed);
            lock.unlock();
            return;
        }

        final long finalPayout = payout;
        final int finalTotalItems = totalItems;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult result = api("PATCH", userUrl(discordId),
                    "{\\"cash\\":" + finalPayout + ",\\"reason\\":\\"" +
                            json("CoolWips SMP automatic sell chest") + "\\"}");
            Bukkit.getScheduler().runTask(this, () -> {
                if (!success(result)) {
                    restoreChestItems(inventory, removed);
                    lock.unlock();
                    return;
                }
                for (Map.Entry<Material, Integer> entry : amounts.entrySet()) {
                    long itemGross = (long) entry.getValue() * prices.get(entry.getKey());
                    long itemPayout = afterTax(itemGross, sellTax);
                    record(new Transaction(info.owner(), Bukkit.getOfflinePlayer(info.owner()).getName(),
                            entry.getKey().name(), entry.getValue(), itemPayout, false, new java.util.Date().toString()));
                }
                Player online = Bukkit.getPlayer(info.owner());
                if (online != null) {
                    online.sendMessage("§aSell chest automatically sold §f" + finalTotalItems + " items §afor §a$" + money(finalPayout) + "§a.");
                }
                lock.unlock();
            });
        });
    }

    private boolean isChestBlock(Block block) {
        return block.getState() instanceof Chest;
    }

    private UUID sellChestOwner(Block block) {
        if (!(block.getState() instanceof Chest chest)) return null;
        String value = chest.getPersistentDataContainer().get(sellChestOwnerKey, PersistentDataType.STRING);
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void markSellChest(Block block, UUID owner) {
        if (!(block.getState() instanceof Chest chest)) return;
        chest.getPersistentDataContainer().set(sellChestOwnerKey, PersistentDataType.STRING, owner.toString());
        chest.update(true, false);

        Block other = adjacentChest(block);
        if (other != null && other.getState() instanceof Chest otherChest) {
            otherChest.getPersistentDataContainer().set(sellChestOwnerKey, PersistentDataType.STRING, owner.toString());
            otherChest.update(true, false);
        }
    }

    private void unmarkSellChest(Block block) {
        if (block.getState() instanceof Chest chest) {
            chest.getPersistentDataContainer().remove(sellChestOwnerKey);
            chest.update(true, false);
        }

        Block other = adjacentChest(block);
        if (other != null && other.getState() instanceof Chest otherChest) {
            otherChest.getPersistentDataContainer().remove(sellChestOwnerKey);
            otherChest.update(true, false);
        }
    }

    private Block adjacentChest(Block block) {
        for (var face : List.of(org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
            Block other = block.getRelative(face);
            if (isChestBlock(other)) return other;
        }
        return null;
    }

    private SellChestInfo getSellChestInfo(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest chest) {
            UUID owner = sellChestOwner(chest.getBlock());
            return owner == null ? null : new SellChestInfo(owner, chest.getBlock().getLocation(), chestKey(chest.getBlock()));
        }

        if (holder instanceof DoubleChest doubleChest) {
            InventoryHolder left = doubleChest.getLeftSide();
            if (left instanceof Chest leftChest) {
                UUID owner = sellChestOwner(leftChest.getBlock());
                if (owner != null) {
                    return new SellChestInfo(owner, leftChest.getBlock().getLocation(), chestKey(leftChest.getBlock()));
                }
            }

            InventoryHolder right = doubleChest.getRightSide();
            if (right instanceof Chest rightChest) {
                UUID owner = sellChestOwner(rightChest.getBlock());
                if (owner != null) {
                    return new SellChestInfo(owner, rightChest.getBlock().getLocation(), chestKey(rightChest.getBlock()));
                }
            }
        }

        return null;
    }

    private String chestKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private void restoreChestItems(Inventory inventory, List<ItemStack> items) {
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftovers = inventory.addItem(item.clone());
            if (leftovers.isEmpty()) continue;

            Location dropLocation = null;
            InventoryHolder holder = inventory.getHolder();
            if (holder instanceof Chest chest) {
                dropLocation = chest.getBlock().getLocation().add(0.5, 0.5, 0.5);
            } else if (holder instanceof DoubleChest doubleChest) {
                InventoryHolder left = doubleChest.getLeftSide();
                if (left instanceof Chest chest) {
                    dropLocation = chest.getBlock().getLocation().add(0.5, 0.5, 0.5);
                }
            }

            if (dropLocation != null) {
                for (ItemStack leftover : leftovers.values()) {
                    dropLocation.getWorld().dropItemNaturally(dropLocation, leftover);
                }
            }
        }
    }

    private record SellChestInfo(UUID owner, Location location, String key) {}

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

    private void createTrade(Player buyer, String targetName, String rawItem, int amount) {
        Player seller = Bukkit.getPlayerExact(targetName);
        if (seller == null || !seller.isOnline()) {
            buyer.sendMessage("§cThat player is not online.");
            return;
        }
        if (seller.getUniqueId().equals(buyer.getUniqueId())) {
            buyer.sendMessage("§cYou cannot trade with yourself.");
            return;
        }

        Material material = matchMaterial(rawItem);
        Long unit = material == null ? null : prices.get(material);
        if (unit == null) {
            buyer.sendMessage("§cThat item is not in the sell-price list.");
            return;
        }
        if (amount > maxItems) {
            buyer.sendMessage("§cYou can trade at most " + maxItems + " items at once.");
            return;
        }

        long total;
        try {
            total = Math.multiplyExact(unit, amount);
        } catch (ArithmeticException e) {
            buyer.sendMessage("§cThat trade is too large.");
            return;
        }
        if (total > maxMoney) {
            buyer.sendMessage("§cThat trade exceeds the $"+money(maxMoney)+" transaction limit.");
            return;
        }
        if (count(seller, material) < amount) {
            buyer.sendMessage("§c" + seller.getName() + " does not have " + amount + "x " + pretty(material) + ".");
            return;
        }
        if (!canFit(buyer, material, amount)) {
            buyer.sendMessage("§cYou do not have enough inventory space for that item.");
            return;
        }

        pendingTrades.put(buyer.getUniqueId(),
                new PendingTrade(buyer.getUniqueId(), seller.getUniqueId(), seller.getName(), material.name(), amount, total,
                        System.currentTimeMillis() + 30_000L));

        buyer.sendMessage("§eTrade offer created:");
        buyer.sendMessage("§7Buy §f" + amount + "x " + pretty(material) + " §7from §f" + seller.getName()
                + " §7for §a$" + money(total) + "§7.");
        buyer.sendMessage("§7Waiting for §f" + seller.getName() + "§7 to accept.");
        seller.sendMessage("§e" + buyer.getName() + " wants to buy §f" + amount + "x "
                + pretty(material) + " §efor §a$" + money(total) + "§e.");
        seller.sendMessage("§7Use §f/pay confirm §7to accept or §f/pay cancel §7to decline.");
    }

    private void payConfirm(Player seller) {
        PendingTrade trade = pendingTrades.values().stream()
                .filter(t -> t.sellerUuid().equals(seller.getUniqueId()))
                .findFirst()
                .orElse(null);

        if (trade == null || trade.expiresAt() < System.currentTimeMillis()) {
            seller.sendMessage("§cYou have no pending item trade.");
            return;
        }

        Player buyer = Bukkit.getPlayer(trade.buyerUuid());
        if (buyer == null || !buyer.isOnline()) {
            removeTrade(trade);
            seller.sendMessage("§cThe buyer is no longer online.");
            return;
        }

        Material material = Material.matchMaterial(trade.material());
        if (material == null) {
            removeTrade(trade);
            seller.sendMessage("§cThat item is no longer valid.");
            return;
        }

        if (count(seller, material) < trade.amount()) {
            removeTrade(trade);
            seller.sendMessage("§cYou no longer have enough of the requested item.");
            buyer.sendMessage("§cThe trade was cancelled because the seller no longer has enough items.");
            return;
        }
        if (!canFit(buyer, material, trade.amount())) {
            removeTrade(trade);
            seller.sendMessage("§cThe buyer no longer has enough inventory space.");
            buyer.sendMessage("§cThe trade was cancelled because your inventory is full.");
            return;
        }

        ReentrantLock buyerLock = locks.computeIfAbsent(buyer.getUniqueId(), k -> new ReentrantLock());
        ReentrantLock sellerLock = locks.computeIfAbsent(seller.getUniqueId(), k -> new ReentrantLock());

        ReentrantLock first = buyer.getUniqueId().toString().compareTo(seller.getUniqueId().toString()) < 0
                ? buyerLock : sellerLock;
        ReentrantLock second = first == buyerLock ? sellerLock : buyerLock;

        if (!first.tryLock()) {
            seller.sendMessage("§eThe buyer currently has another transaction processing.");
            return;
        }
        if (!second.tryLock()) {
            first.unlock();
            seller.sendMessage("§eThe other player currently has another transaction processing.");
            return;
        }

        removeTrade(trade);
        String buyerDiscord = linkedId(buyer);
        String sellerDiscord = linkedId(seller);
        if (buyerDiscord == null || sellerDiscord == null) {
            second.unlock();
            first.unlock();
            return;
        }

        seller.sendMessage("§7Processing the trade...");
        buyer.sendMessage("§7Processing your trade with §f" + seller.getName() + "§7...");

        final long total = trade.total();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpResult debit = api("PATCH", userUrl(buyerDiscord),
                    "{\"cash\":" + (-total) + ",\"reason\":\"Player item trade purchase\"}");

            if (!success(debit)) {
                first.unlock();
                second.unlock();
                Bukkit.getScheduler().runTask(this, () -> {
                    buyer.sendMessage("§cTrade cancelled. Buyer payment failed (HTTP " + debit.status + ").");
                    seller.sendMessage("§cTrade cancelled because the buyer's payment failed.");
                });
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                if (!buyer.isOnline() || !seller.isOnline()
                        || count(seller, material) < trade.amount()
                        || !canFit(buyer, material, trade.amount())) {
                    reverseMoney(buyerDiscord, total, "Player item trade reversal", null, null);
                    buyer.sendMessage("§cTrade cancelled. Conditions changed, so your payment is being reversed.");
                    seller.sendMessage("§cTrade cancelled. Conditions changed, so the payment is being reversed.");
                    first.unlock();
                    second.unlock();
                    return;
                }

                final List<ItemStack> tradedItems = takeItems(seller, material, trade.amount());
                if (tradedItems.stream().mapToInt(ItemStack::getAmount).sum() != trade.amount()) {
                    restoreItems(seller, tradedItems);
                    reverseMoney(buyerDiscord, total, "Player item trade reversal", null, null);
                    buyer.sendMessage("§cTrade cancelled. Your payment is being reversed.");
                    seller.sendMessage("§cTrade cancelled because the item transfer could not be completed.");
                    first.unlock();
                    second.unlock();
                    return;
                }

                List<ItemStack> leftovers = giveItems(buyer, tradedItems);
                if (!leftovers.isEmpty()) {
                    restoreItems(seller, tradedItems);
                    reverseMoney(buyerDiscord, total, "Player item trade reversal", null, null);
                    buyer.sendMessage("§cTrade cancelled. Your payment is being reversed.");
                    seller.sendMessage("§cTrade cancelled because the item could not be delivered.");
                    first.unlock();
                    second.unlock();
                    return;
                }

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    HttpResult credit = api("PATCH", userUrl(sellerDiscord),
                            "{\"cash\":" + total + ",\"reason\":\"Player item trade sale\"}");

                    if (!success(credit)) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            restoreItems(seller, tradedItems);
                            buyer.sendMessage("§cSeller payment failed. Your payment is being reversed.");
                            seller.sendMessage("§cTrade payment failed. The items were returned.");
                            reverseMoney(buyerDiscord, total, "Player item trade reversal", null, null);
                            first.unlock();
                            second.unlock();
                        });
                        return;
                    }

                    Bukkit.getScheduler().runTask(this, () -> {
                        record(new Transaction(buyer.getUniqueId(), buyer.getName(), material.name(),
                                trade.amount(), total, true, new java.util.Date().toString()));
                        record(new Transaction(seller.getUniqueId(), seller.getName(), material.name(),
                                trade.amount(), total, false, new java.util.Date().toString()));
                        buyer.sendMessage("§aTrade complete: §f" + trade.amount() + "x "
                                + pretty(material) + " §afor §c$" + money(total) + "§a.");
                        seller.sendMessage("§aTrade complete: §f" + trade.amount() + "x "
                                + pretty(material) + " §afor §a$" + money(total) + "§a.");
                        first.unlock();
                        second.unlock();
                    });
                });
            });
        });
    }

    private void removeTrade(PendingTrade trade) {
        pendingTrades.remove(trade.buyerUuid());
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

        long baseCost;
        try {
            baseCost = Math.multiplyExact(unit, requested);
        } catch (ArithmeticException e) {
            p.sendMessage("§cThat purchase is too large.");
            return;
        }

        long cost = Math.max(0, Math.round(baseCost * (1.0 + buyTax)));

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

                record(new Transaction(p.getUniqueId(), p.getName(), finalMaterial.name(),
                        amount, money, true, new java.util.Date().toString()));
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
                getLogger().severe("Could not reverse $" + money + " for "
                        + (p == null ? "an economy transaction" : p.getName())
                        + ". UnbelievaBoat HTTP " + reverse.status);
            }
            if (lock != null) {
                lock.unlock();
            }
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

    private List<ItemStack> takeItems(Player p, Material material, int amount) {
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] contents = p.getInventory().getStorageContents();
        int left = amount;

        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;

            int take = Math.min(left, stack.getAmount());
            ItemStack part = stack.clone();
            part.setAmount(take);
            taken.add(part);

            if (take == stack.getAmount()) contents[i] = null;
            else stack.setAmount(stack.getAmount() - take);
            left -= take;
        }

        p.getInventory().setStorageContents(contents);
        return taken;
    }

    private List<ItemStack> giveItems(Player p, List<ItemStack> items) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack item : items) {
            Map<Integer, ItemStack> result = p.getInventory().addItem(item.clone());
            leftovers.addAll(result.values());
        }
        return leftovers;
    }

    private void restoreItems(Player p, List<ItemStack> items) {
        List<ItemStack> leftovers = giveItems(p, items);
        for (ItemStack stack : leftovers) {
            p.getWorld().dropItemNaturally(p.getLocation(), stack);
        }
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
    private record PendingTrade(UUID buyerUuid, UUID sellerUuid, String sellerName, String material, int amount,
                                long total, long expiresAt) {}
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

    private void showHistory(Player p) {
        List<Transaction> mine;
        synchronized (history) {
            mine = history.stream()
                    .filter(t -> t.uuid().equals(p.getUniqueId()))
                    .limit(10)
                    .toList();
        }

        p.sendMessage("§6§lCoolWips Economy History");
        if (mine.isEmpty()) {
            p.sendMessage("§7No transactions recorded since the plugin was started.");
            return;
        }

        for (Transaction t : mine) {
            String action = t.purchase() ? "Bought" : "Sold";
            String sign = t.purchase() ? "§c-$" : "§a+$";
            p.sendMessage("§7" + action + " §f" + t.amount() + "x " + pretty(Material.matchMaterial(t.material()))
                    + " §7for " + sign + money(t.money()));
        }
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

        if ((name.equals("sellto") || name.equals("buyfrom")) && args.length == 2) {
            String query = args[1].toUpperCase(Locale.ROOT);
            return prices.keySet().stream().map(Enum::name)
                    .filter(x -> x.startsWith(query)).sorted().limit(50).toList();
        }

        if (name.equals("pay") && args.length == 1) {
            return List.of("confirm", "cancel");
        }

        if (name.equals("pay") && args.length == 2) {
            String query = args[1].toUpperCase(Locale.ROOT);
            return prices.keySet().stream().map(Enum::name)
                    .filter(x -> x.startsWith(query)).sorted().limit(50).toList();
        }

        if (name.equals("sellchest") && args.length == 1) {
            return List.of("create", "remove", "status");
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
