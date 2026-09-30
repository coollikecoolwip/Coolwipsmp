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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.event.block.Action;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.bukkit.configuration.file.YamlConfiguration;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CoolWipsEconomy extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final Pattern BANK = Pattern.compile("\"bank\"\\s*:\\s*(-?\\d+)");
    private final Map<Material, BigDecimal> prices = new ConcurrentHashMap<>();
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
    private final Map<UUID, Bounty> bounties = new ConcurrentHashMap<>();
    private final ReentrantLock bountyStateLock = new ReentrantLock();
    private final Deque<Transaction> history = new ArrayDeque<>();
    private int transactionCooldownMs;
    private int confirmationSeconds;
    private double sellTax, buyTax;
    private long minimumBounty, maximumBounty;
    private NamespacedKey sellChestOwnerKey;
    private final Map<String, ReentrantLock> sellChestLocks = new ConcurrentHashMap<>();
    private final Set<String> pendingAutomaticSellChests = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> automaticSellChestCooldowns = new ConcurrentHashMap<>();
    private final Set<String> scheduledAutomaticSellChestRetries = ConcurrentHashMap.newKeySet();
    private static final long AUTOMATIC_SELL_CHEST_COOLDOWN_MS = 30_000L;
    
    // Dynamic market for renewable/farm outputs: normal volumes keep full value,
    // heavy volume lowers only that item's sell price until the configured floor.
    private static final long DEFAULT_MARKET_FREE_UNITS = 2048L;
    private static final long DEFAULT_MARKET_STEP_UNITS = 2048L;
    private static final BigDecimal DEFAULT_MARKET_DROP_PERCENT = new BigDecimal("0.10");
    private static final BigDecimal DEFAULT_MARKET_MIN_PRICE = new BigDecimal("90");

    private static final Set<Material> FARM_INCOME_MATERIALS = EnumSet.of(
            Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.BEETROOT,
            Material.MELON_SLICE, Material.MELON, Material.PUMPKIN, Material.SUGAR_CANE, Material.BAMBOO,
            Material.BAMBOO_BLOCK, Material.CACTUS, Material.COCOA_BEANS, Material.NETHER_WART,
            Material.SWEET_BERRIES, Material.GLOW_BERRIES, Material.CHORUS_FRUIT, Material.CHORUS_FLOWER,
            Material.KELP, Material.DRIED_KELP, Material.DRIED_KELP_BLOCK, Material.APPLE, Material.BREAD,
            Material.COOKIE, Material.CAKE, Material.BAKED_POTATO, Material.PAPER, Material.BOOK,
            Material.BOOKSHELF, Material.CHEST, Material.BARREL, Material.STICK, Material.HAY_BLOCK,
            Material.BONE_BLOCK, Material.NETHER_WART_BLOCK, Material.SLIME_BLOCK, Material.TNT,
            Material.FIREWORK_ROCKET, Material.FIREWORK_STAR,
            Material.BEEF, Material.PORKCHOP, Material.CHICKEN, Material.MUTTON, Material.RABBIT,
            Material.COD, Material.SALMON, Material.PUFFERFISH, Material.TROPICAL_FISH,
            Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN, Material.COOKED_MUTTON,
            Material.COOKED_RABBIT, Material.COOKED_COD, Material.COOKED_SALMON,
            Material.LEATHER, Material.FEATHER, Material.EGG, Material.ROTTEN_FLESH, Material.BONE,
            Material.BONE_MEAL, Material.ARROW, Material.STRING, Material.SPIDER_EYE, Material.GUNPOWDER,
            Material.ENDER_PEARL, Material.BLAZE_ROD, Material.SLIME_BALL, Material.MAGMA_CREAM,
            Material.GHAST_TEAR, Material.PHANTOM_MEMBRANE, Material.INK_SAC, Material.GLOW_INK_SAC,
            Material.RABBIT_FOOT, Material.PRISMARINE_SHARD, Material.PRISMARINE_CRYSTALS,
            Material.NAUTILUS_SHELL, Material.HONEYCOMB, Material.HONEY_BOTTLE, Material.HONEY_BLOCK,
            Material.HONEYCOMB_BLOCK,
            Material.MOSS_BLOCK, Material.MOSS_CARPET, Material.AZALEA, Material.FLOWERING_AZALEA,
            Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES, Material.PINK_PETALS,
            Material.WILDFLOWERS, Material.LEAF_LITTER, Material.BUSH, Material.FIREFLY_BUSH,
            Material.SHORT_DRY_GRASS, Material.TALL_DRY_GRASS, Material.SHORT_GRASS, Material.TALL_GRASS,
            Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH, Material.SEAGRASS,
            Material.SMALL_DRIPLEAF, Material.BIG_DRIPLEAF, Material.HANGING_ROOTS, Material.SPORE_BLOSSOM,
            Material.TORCHFLOWER, Material.PITCHER_PLANT, Material.CACTUS_FLOWER, Material.OPEN_EYEBLOSSOM,
            Material.CLOSED_EYEBLOSSOM, Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID,
            Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
            Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY, Material.CORNFLOWER,
            Material.LILY_OF_THE_VALLEY, Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH,
            Material.PEONY, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.VINE,
            Material.GLOW_LICHEN, Material.LILY_PAD, Material.SEA_PICKLE,
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
            Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
            Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
            Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL,
            Material.TOTEM_OF_UNDYING
    );

    private static final Set<String> FARM_WOOD_PREFIXES = Set.of(
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY",
            "PALE_OAK", "BAMBOO", "CRIMSON", "WARPED"
    );

    private final Map<Material, Long> marketSoldToday = new ConcurrentHashMap<>();
    private final ReentrantLock marketLock = new ReentrantLock();
    private File marketFile;
    private YamlConfiguration marketData;
    private String marketDay;
    private long marketFreeUnits;
    private long marketStepUnits;
    private BigDecimal marketDropPercent;
    private BigDecimal marketMinPrice;
    private BigDecimal marketMossMinPrice;

    private record MarketSale(Material material, int amount, BigDecimal gross, boolean marketTracked) {}


    private static final Set<String> UNSAFE_SELL_MATERIALS = Set.of(
            "BEDROCK", "BARRIER", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK",
            "REPEATING_COMMAND_BLOCK", "STRUCTURE_BLOCK", "STRUCTURE_VOID",
            "JIGSAW", "SPAWNER", "TRIAL_SPAWNER", "VAULT",
            "REINFORCED_DEEPSLATE", "END_PORTAL_FRAME", "END_PORTAL",
            "END_GATEWAY", "LIGHT", "DEBUG_STICK", "KNOWLEDGE_BOOK"
    );

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout())).build();
        sellChestOwnerKey = new NamespacedKey(this, "sell-chest-owner");
        loadMarketLedger();
        Bukkit.getPluginManager().registerEvents(this, this);

        for (String name : List.of("sell","sellall","prices","balance","cweconomy","buy","shop","pay","sellto","buyfrom","sellchest","history","bounty")) {
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
        startNonNegativeBalanceGuard();
    }

    @Override public void onDisable() {
        saveMarketLedger();
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
        minimumBounty = Math.max(1L, getConfig().getLong("settings.minimum-bounty", 100));
        maximumBounty = Math.max(minimumBounty, getConfig().getLong("settings.maximum-bounty", 1000000));
        sellTax = Math.max(0, Math.min(1, getConfig().getDouble("settings.sell-tax", 0.05)));
        buyTax = Math.max(0, Math.min(1, getConfig().getDouble("settings.buy-tax", 0.05)));
        marketFreeUnits = Math.max(0L, getConfig().getLong("settings.market-full-price-units", DEFAULT_MARKET_FREE_UNITS));
        marketStepUnits = Math.max(1L, getConfig().getLong("settings.market-step-units", DEFAULT_MARKET_STEP_UNITS));
        marketDropPercent = parseDecimalSetting("settings.market-price-drop", DEFAULT_MARKET_DROP_PERCENT)
                .max(BigDecimal.ZERO).min(new BigDecimal("0.99"));
        marketMinPrice = parseDecimalSetting("settings.market-min-price", DEFAULT_MARKET_MIN_PRICE)
                .max(BigDecimal.ZERO);
        marketMossMinPrice = parseDecimalSetting("settings.market-moss-min-price", new BigDecimal("0.017"))
                .max(BigDecimal.ZERO);

        sellsDisabled = getConfig().getBoolean("maintenance.all-sells-disabled", false);
        maintenanceBlocks.clear();
        for (String name : getConfig().getStringList("maintenance.blocked-items")) {
            Material m = Material.matchMaterial(name.replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT));
            if (m != null) maintenanceBlocks.add(m);
        }

        prices.clear();
        prices.putAll(readConfigSellPrices("prices"));

        shopPrices.clear();
        shopPrices.putAll(readConfigPrices("shop"));
        validateShopPrices();
        loadBounties();
    }

    private Map<Material, BigDecimal> readConfigSellPrices(String sectionName) {
        Map<Material, BigDecimal> result = new HashMap<>();
        var section = getConfig().getConfigurationSection(sectionName);
        if (section == null) return result;

        for (String key : section.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            String raw = getConfig().getString(sectionName + "." + key, "");
            try {
                BigDecimal value = new BigDecimal(raw);
                if (m != null && value.signum() > 0) result.put(m, value);
            } catch (NumberFormatException ignored) {
                getLogger().warning("Ignoring invalid sell price in config: " + key);
            }
        }
        return result;
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

    private BigDecimal parseDecimalSetting(String path, BigDecimal fallback) {
        String raw = getConfig().getString(path, fallback.toPlainString());
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            getLogger().warning("Invalid decimal setting " + path + "; using " + fallback + ".");
            return fallback;
        }
    }

    private void loadMarketLedger() {
        marketLock.lock();
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("Could not create plugin data folder for market ledger.");
            }
            marketFile = new File(getDataFolder(), "market.yml");
            marketData = YamlConfiguration.loadConfiguration(marketFile);
            marketDay = LocalDate.now(ZoneId.systemDefault()).toString();

            if (!marketDay.equals(marketData.getString("day", ""))) {
                marketSoldToday.clear();
                marketData.set("sold", null);
                marketData.set("day", marketDay);
                saveMarketLedgerLocked();
                return;
            }

            var section = marketData.getConfigurationSection("sold");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        Material material = Material.matchMaterial(key);
                        long sold = Long.parseLong(marketData.getString("sold." + key, "0"));
                        if (material != null && sold > 0) marketSoldToday.put(material, sold);
                    } catch (Exception ignored) {
                        getLogger().warning("Ignoring invalid market ledger entry: " + key);
                    }
                }
            }
        } finally {
            marketLock.unlock();
        }
    }

    private void saveMarketLedger() {
        marketLock.lock();
        try {
            saveMarketLedgerLocked();
        } finally {
            marketLock.unlock();
        }
    }

    private void saveMarketLedgerLocked() {
        if (marketFile == null || marketData == null) return;
        marketData.set("day", marketDay);
        marketData.set("sold", null);
        for (Map.Entry<Material, Long> entry : marketSoldToday.entrySet()) {
            marketData.set("sold." + entry.getKey().name(), entry.getValue());
        }
        try {
            marketData.save(marketFile);
        } catch (IOException e) {
            getLogger().warning("Could not save market ledger: " + e.getMessage());
        }
    }

    private void refreshMarketDayLocked() {
        String today = LocalDate.now(ZoneId.systemDefault()).toString();
        if (today.equals(marketDay)) return;
        marketDay = today;
        marketSoldToday.clear();
        saveMarketLedgerLocked();
    }

    private boolean isFarmIncomeMaterial(Material material) {
        if (material == null) return false;
        if (FARM_INCOME_MATERIALS.contains(material)) return true;
        String name = material.name();
        for (String prefix : FARM_WOOD_PREFIXES) {
            if (name.startsWith(prefix + "_") || name.equals(prefix)) return true;
        }
        return name.contains("WOOL") || name.endsWith("_CARPET") || name.endsWith("_BED");
    }

    private BigDecimal marketUnitPrice(Material material, long sold) {
        BigDecimal base = prices.get(material);
        if (base == null || !isFarmIncomeMaterial(material)) return base;
        if (sold < marketFreeUnits || marketStepUnits <= 0 || marketDropPercent.signum() <= 0) return base;

        long steps = 1L + (sold - marketFreeUnits) / marketStepUnits;
        BigDecimal multiplier = BigDecimal.ONE.subtract(marketDropPercent)
                .pow((int) Math.min(steps, 1000L));
        BigDecimal price = base.multiply(multiplier);
        BigDecimal floor = material == Material.MOSS_BLOCK
                ? (marketMossMinPrice == null ? new BigDecimal("0.017") : marketMossMinPrice)
                : marketMinPrice;
        if (base.compareTo(floor) > 0 && price.compareTo(floor) < 0) {
            price = floor;
        }
        return price;
    }

    private BigDecimal marketGrossForSale(Material material, long sold, int amount) {
        BigDecimal base = prices.get(material);
        if (base == null || amount <= 0) return BigDecimal.ZERO;
        if (!isFarmIncomeMaterial(material)) return base.multiply(BigDecimal.valueOf(amount));

        long remaining = amount;
        long cursor = sold;
        BigDecimal gross = BigDecimal.ZERO;
        while (remaining > 0) {
            BigDecimal unit = marketUnitPrice(material, cursor);
            long nextBoundary;
            if (cursor < marketFreeUnits) {
                nextBoundary = marketFreeUnits;
            } else {
                long stepIndex = (cursor - marketFreeUnits) / marketStepUnits;
                nextBoundary = marketFreeUnits + Math.multiplyExact(stepIndex + 1L, marketStepUnits);
            }
            long chunk = Math.min(remaining, Math.max(1L, nextBoundary - cursor));
            gross = gross.add(unit.multiply(BigDecimal.valueOf(chunk)));
            cursor += chunk;
            remaining -= chunk;
        }
        return gross;
    }

    private BigDecimal currentSellPrice(Material material) {
        BigDecimal base = prices.get(material);
        if (base == null || !isFarmIncomeMaterial(material)) return base;
        marketLock.lock();
        try {
            refreshMarketDayLocked();
            return marketUnitPrice(material, marketSoldToday.getOrDefault(material, 0L));
        } finally {
            marketLock.unlock();
        }
    }

    private MarketSale reserveMarketSale(Material material, int amount) {
        BigDecimal base = prices.get(material);
        if (base == null || amount <= 0) return null;
        if (!isFarmIncomeMaterial(material)) {
            return new MarketSale(material, amount, base.multiply(BigDecimal.valueOf(amount)), false);
        }
        marketLock.lock();
        try {
            refreshMarketDayLocked();
            long sold = marketSoldToday.getOrDefault(material, 0L);
            BigDecimal gross = marketGrossForSale(material, sold, amount);
            long newSold = Math.addExact(sold, amount);
            marketSoldToday.put(material, newSold);
            saveMarketLedgerLocked();
            return new MarketSale(material, amount, gross, true);
        } catch (ArithmeticException e) {
            return null;
        } catch (RuntimeException e) {
            getLogger().warning("Could not reserve market sale for " + material + ": " + e.getMessage());
            return null;
        } finally {
            marketLock.unlock();
        }
    }

    private Map<Material, MarketSale> reserveMarketBatch(Map<Material, Integer> amounts) {
        Map<Material, MarketSale> result = new LinkedHashMap<>();
        marketLock.lock();
        try {
            refreshMarketDayLocked();
            Map<Material, Long> newTotals = new HashMap<>();
            for (Map.Entry<Material, Integer> entry : amounts.entrySet()) {
                Material material = entry.getKey();
                int amount = entry.getValue();
                BigDecimal base = prices.get(material);
                if (base == null || amount <= 0) continue;

                boolean tracked = isFarmIncomeMaterial(material);
                long sold = marketSoldToday.getOrDefault(material, 0L);
                BigDecimal gross = tracked
                        ? marketGrossForSale(material, sold, amount)
                        : base.multiply(BigDecimal.valueOf(amount));
                if (tracked) newTotals.put(material, Math.addExact(sold, amount));
                result.put(material, new MarketSale(material, amount, gross, tracked));
            }

            if (result.size() != amounts.size()) return Collections.emptyMap();
            for (Map.Entry<Material, Long> entry : newTotals.entrySet()) {
                marketSoldToday.put(entry.getKey(), entry.getValue());
            }
            if (!newTotals.isEmpty()) saveMarketLedgerLocked();
            return result;
        } catch (ArithmeticException e) {
            return Collections.emptyMap();
        } finally {
            marketLock.unlock();
        }
    }

    private void releaseMarketSale(MarketSale sale) {
        if (sale == null || !sale.marketTracked()) return;
        marketLock.lock();
        try {
            refreshMarketDayLocked();
            long current = marketSoldToday.getOrDefault(sale.material(), 0L);
            long restored = Math.max(0L, current - sale.amount());
            if (restored == 0L) marketSoldToday.remove(sale.material());
            else marketSoldToday.put(sale.material(), restored);
            saveMarketLedgerLocked();
        } finally {
            marketLock.unlock();
        }
    }

    private void releaseMarketBatch(Map<Material, MarketSale> sales) {
        if (sales == null || sales.isEmpty()) return;
        marketLock.lock();
        try {
            refreshMarketDayLocked();
            for (MarketSale sale : sales.values()) {
                if (!sale.marketTracked()) continue;
                long current = marketSoldToday.getOrDefault(sale.material(), 0L);
                long restored = Math.max(0L, current - sale.amount());
                if (restored == 0L) marketSoldToday.remove(sale.material());
                else marketSoldToday.put(sale.material(), restored);
            }
            saveMarketLedgerLocked();
        } finally {
            marketLock.unlock();
        }
    }

    private void loadRemotePrices() {
        loadRemoteSellFile(pricesUrl, "prices.txt", prices);
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
                validateShopPrices();
                getLogger().info("Loaded " + loaded.size() + " " + label + " entries from GitHub.");
            } catch (Exception e) {
                getLogger().warning("Could not load remote " + label + ": "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    private void validateShopPrices() {
        if (prices.isEmpty() || shopPrices.isEmpty()) return;
        List<Material> invalid = new ArrayList<>();
        for (Map.Entry<Material, Long> entry : shopPrices.entrySet()) {
            BigDecimal sellPrice = prices.get(entry.getKey());
            if (sellPrice == null) continue;
            long sellPayout = afterTax(sellPrice, sellTax);
            long chargedBuy = Math.max(0, Math.round(entry.getValue() * (1.0 + buyTax)));
            if (chargedBuy <= sellPayout) {
                invalid.add(entry.getKey());
                getLogger().warning("Blocked unsafe shop price for " + entry.getKey()
                        + ": charged buy $" + chargedBuy
                        + " would not exceed its post-tax sell payout of $" + sellPayout + ".");
            }
        }
        for (Material material : invalid) shopPrices.remove(material);
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
                if (UNSAFE_SELL_MATERIALS.contains(materialName) && label.equals("prices.txt")) {
                    getLogger().warning("Ignoring unsafe survival-inaccessible material in " + label + ": " + materialName);
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

    private void loadRemoteSellFile(String fileUrl, String label, Map<Material, BigDecimal> destination) {
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

                Map<Material, BigDecimal> loaded = parseSellPrices(response.body(), label);
                if (loaded.isEmpty()) {
                    getLogger().warning("Remote " + label + " contained no valid prices. Keeping local values.");
                    return;
                }

                destination.clear();
                destination.putAll(loaded);
                validateShopPrices();
                getLogger().info("Loaded " + loaded.size() + " " + label + " entries from GitHub.");
            } catch (Exception e) {
                getLogger().warning("Could not load remote " + label + ": "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    private Map<Material, BigDecimal> parseSellPrices(String text, String label) {
        Map<Material, BigDecimal> loaded = new HashMap<>();

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
                BigDecimal value = new BigDecimal(line.substring(separator + 1).trim());
                Material material = Material.matchMaterial(materialName);

                if (material == null) {
                    getLogger().warning("Ignoring unknown material in " + label + ": " + materialName);
                    continue;
                }
                if (UNSAFE_SELL_MATERIALS.contains(materialName) && label.equals("prices.txt")) {
                    getLogger().warning("Ignoring unsafe survival-inaccessible material in " + label + ": " + materialName);
                    continue;
                }
                if (value.signum() <= 0) {
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

                if (args.length == 2) {
                    try {
                        long money = Long.parseLong(args[1]);
                        payCash(p, args[0], money);
                        return true;
                    } catch (NumberFormatException ignored) {
                        // Keep supporting /pay <player> <item> syntax.
                    }
                }

                if (args.length < 2 || args.length > 3) {
                    p.sendMessage("§cUsage: /pay <player> <amount>");
                    p.sendMessage("§7Item trade: /pay <player> <item> [amount]");
                    return true;
                }

                int amount = parseAmount(p, args.length == 3 ? args[2] : "1");
                if (amount < 1) return true;
                createTrade(p, args[0], args[1], amount);
                return true;
            }

            case "bounty" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use /bounty.");
                    return true;
                }
                if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
                    listBounties(p);
                    return true;
                }
                if (args.length != 2) {
                    p.sendMessage("§cUsage: /bounty <player> <amount>");
                    p.sendMessage("§7Use /bounty list to view active bounties.");
                    return true;
                }
                long amount = parseMoneyAmount(p, args[1], minimumBounty, Math.min(maximumBounty, maxMoney));
                if (amount < 1) return true;
                postBounty(p, args[0], amount);
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
                showPaged(sender, currentSellPrices(), page, "CoolWips Sell Prices", "/prices");
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
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (!isCraftingConversionSafe(event.getInventory().getMatrix(), result)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        ItemStack result = event.getRecipe() == null ? null : event.getRecipe().getResult();
        if (!isCraftingConversionSafe(event.getInventory().getMatrix(), result)) {
            event.setCancelled(true);
        }
    }

    private boolean isCraftingConversionSafe(ItemStack[] matrix, ItemStack result) {
        if (result == null || result.getType().isAir() || result.getAmount() <= 0) return true;

        BigDecimal resultSell = currentSellPrice(result.getType());
        if (resultSell == null) return true;

        BigDecimal inputBuyCost = BigDecimal.ZERO;
        BigDecimal inputSellPayout = BigDecimal.ZERO;
        boolean allInputsBuyable = true;
        boolean allInputsSellable = true;
        boolean hasInput = false;

        for (ItemStack input : matrix) {
            if (input == null || input.getType().isAir() || input.getAmount() <= 0) continue;
            hasInput = true;

            Long buyUnit = shopPrices.get(input.getType());
            if (buyUnit == null) {
                allInputsBuyable = false;
            } else {
                inputBuyCost = inputBuyCost.add(
                        BigDecimal.valueOf(buyUnit)
                                .multiply(BigDecimal.valueOf(input.getAmount()))
                                .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(buyTax))));
            }

            BigDecimal sellUnit = currentSellPrice(input.getType());
            if (sellUnit == null) {
                allInputsSellable = false;
            } else {
                inputSellPayout = inputSellPayout.add(
                        sellUnit.multiply(BigDecimal.valueOf(input.getAmount())));
            }
        }

        if (!hasInput) return true;

        BigDecimal resultGross = resultSell.multiply(BigDecimal.valueOf(result.getAmount()));
        long resultPayout = afterTax(resultGross, sellTax);

        // Block buy -> craft -> sell arbitrage.
        if (allInputsBuyable
                && BigDecimal.valueOf(resultPayout).compareTo(inputBuyCost) >= 0) {
            return false;
        }

        // Block profitable item-to-item conversions, including reverse recipes
        // such as a block being crafted back into nine ingots.
        if (allInputsSellable) {
            long inputPayout = afterTax(inputSellPayout, sellTax);
            if (resultPayout >= inputPayout) {
                return false;
            }
        }

        return true;
    }

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player target = event.getEntity();
        Player killer = target.getKiller();
        if (killer == null || killer.getUniqueId().equals(target.getUniqueId())) return;

        String killerDiscord = linkedId(killer);
        if (killerDiscord == null) return;

        Bounty bounty;
        bountyStateLock.lock();
        try {
            bounty = bounties.remove(target.getUniqueId());
            if (bounty == null) return;
            try {
                saveBounties();
            } catch (Exception e) {
                bounties.put(target.getUniqueId(), bounty);
                getLogger().severe("Could not save bounty claim for " + target.getName() + ": " + e.getMessage());
                return;
            }
        } finally {
            bountyStateLock.unlock();
        }

        ReentrantLock killerLock = locks.computeIfAbsent(killer.getUniqueId(), k -> new ReentrantLock());
        if (!killerLock.tryLock()) {
            bountyStateLock.lock();
            try {
                bounties.merge(target.getUniqueId(), bounty,
                        (existing, ignored) -> new Bounty(existing.targetName(), existing.amount() + bounty.amount()));
                saveBounties();
            } catch (Exception e) {
                getLogger().severe("Could not restore bounty after payout lock failure for " + target.getName() + ".");
            } finally {
                bountyStateLock.unlock();
            }
            killer.sendMessage("§cBounty payout delayed because you have another economy transaction processing.");
            return;
        }

        final long payout = bounty.amount();
        final String targetName = bounty.targetName();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            BankMutationResult result = changeBank(killerDiscord, payout,
                    "CoolWips SMP bounty claimed on " + targetName, null);

            if (result.state() == BankMutationState.NOT_APPLIED) {
                Bukkit.getScheduler().runTask(this, () -> {
                    bountyStateLock.lock();
                    try {
                        bounties.merge(target.getUniqueId(), bounty,
                                (existing, ignored) -> new Bounty(existing.targetName(), existing.amount() + bounty.amount()));
                        try {
                            saveBounties();
                        } catch (Exception e) {
                            getLogger().severe("Could not restore failed bounty payout for " + targetName + ": " + e.getMessage());
                        }
                    } finally {
                        bountyStateLock.unlock();
                    }
                    killer.sendMessage("§cBounty payout failed. The bounty was restored.");
                    killerLock.unlock();
                });
                return;
            }

            if (result.state() == BankMutationState.UNKNOWN) {
                Bukkit.getScheduler().runTask(this, () -> {
                    getLogger().severe("Bounty payout for " + targetName + " could not be verified. " +
                            "The bounty was not automatically reissued to prevent a duplicate payout.");
                    killer.sendMessage("§cBounty payout could not be verified. Do not retry; contact staff.");
                    killerLock.unlock();
                });
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                killer.sendMessage("§6§lBOUNTY CLAIMED §e+$" + money(payout) + " §7for killing §f" + targetName + "§7.");
                Bukkit.broadcastMessage("§6§lBOUNTY §f" + killer.getName() + " §7claimed a §a$" +
                        money(payout) + " §7bounty on §f" + targetName + "§7.");
                killerLock.unlock();
            });
        });
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
    public void onSellChestInventoryClick(InventoryClickEvent event) {
        Inventory inventory = event.getInventory();
        if (!isChestInventory(inventory)) return;
        if (getSellChestInfo(inventory) == null) return;
        Bukkit.getScheduler().runTask(this, () -> scheduleAutomaticSellChest(inventory));
    }

    @EventHandler
    public void onSellChestInventoryDrag(InventoryDragEvent event) {
        Inventory inventory = event.getInventory();
        if (!isChestInventory(inventory)) return;
        if (getSellChestInfo(inventory) == null) return;
        Bukkit.getScheduler().runTask(this, () -> scheduleAutomaticSellChest(inventory));
    }

    @EventHandler
    public void onSellChestClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!isChestInventory(inventory)) return;
        scheduleAutomaticSellChest(inventory);
    }

    @EventHandler
    public void onSellChestMove(InventoryMoveItemEvent event) {
        Inventory inventory = event.getDestination();
        if (!isChestInventory(inventory)) return;
        scheduleAutomaticSellChest(inventory);
    }

    private boolean isChestInventory(Inventory inventory) {
        if (inventory == null) return false;
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof Chest || holder instanceof DoubleChest;
    }

    private void scheduleAutomaticSellChest(Inventory inventory) {
        if (!isChestInventory(inventory)) return;

        SellChestInfo info = getSellChestInfo(inventory);
        if (info == null) return;

        String key = info.key();
        if (!pendingAutomaticSellChests.add(key)) return;

        long now = System.currentTimeMillis();
        Long lastSell = automaticSellChestCooldowns.get(key);
        long delayTicks = 2L;
        if (lastSell != null) {
            long remainingMs = AUTOMATIC_SELL_CHEST_COOLDOWN_MS - (now - lastSell);
            if (remainingMs > 0) {
                scheduleAutomaticSellChestRetry(inventory, key, remainingMs);
                pendingAutomaticSellChests.remove(key);
                return;
            }
        }

        // Batch rapid clicks/dragging/hopper transfers into one sell check.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            pendingAutomaticSellChests.remove(key);
            Long latestSell = automaticSellChestCooldowns.get(key);
            if (latestSell != null) {
                long remainingMs = AUTOMATIC_SELL_CHEST_COOLDOWN_MS - (System.currentTimeMillis() - latestSell);
                if (remainingMs > 0) {
                    scheduleAutomaticSellChestRetry(inventory, key, remainingMs);
                    return;
                }
            }
            processAutomaticSellChest(inventory);
        }, delayTicks);
    }

    private void scheduleAutomaticSellChestRetry(Inventory inventory, String key, long remainingMs) {
        if (!scheduledAutomaticSellChestRetries.add(key)) return;
        long ticks = Math.max(1L, (remainingMs + 49L) / 50L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            scheduledAutomaticSellChestRetries.remove(key);
            scheduleAutomaticSellChest(inventory);
        }, ticks);
    }

    private void processAutomaticSellChest(Inventory inventory) {
        SellChestInfo info = getSellChestInfo(inventory);
        if (info == null || sellsDisabled) return;

        ReentrantLock lock = sellChestLocks.computeIfAbsent(info.key(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            String key = info.key();
            if (scheduledAutomaticSellChestRetries.add(key)) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    scheduledAutomaticSellChestRetries.remove(key);
                    scheduleAutomaticSellChest(inventory);
                }, 10L);
            }
            return;
        }

        ReentrantLock playerLock = locks.computeIfAbsent(info.owner(), k -> new ReentrantLock());
        if (!playerLock.tryLock()) {
            lock.unlock();
            String key = info.key();
            if (scheduledAutomaticSellChestRetries.add(key)) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    scheduledAutomaticSellChestRetries.remove(key);
                    scheduleAutomaticSellChest(inventory);
                }, 10L);
            }
            return;
        }

        Map<Material, Integer> amounts = new LinkedHashMap<>();
        int totalItems = 0;

        // Sell up to the same per-transaction item limit used by /sell and /sellall.
        // Do not reject the entire chest just because it contains more than maxItems.
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            BigDecimal unit = prices.get(stack.getType());
            if (unit == null || maintenanceBlocks.contains(stack.getType())) continue;

            int remainingCapacity = maxItems - totalItems;
            if (remainingCapacity <= 0) break;

            int amount = Math.min(stack.getAmount(), remainingCapacity);
            if (amount <= 0) continue;
            totalItems += amount;
            amounts.merge(stack.getType(), amount, Integer::sum);
        }

        if (amounts.isEmpty()) {
            playerLock.unlock();
            lock.unlock();
            return;
        }

        // Remove only the items actually included in this batch.
        List<ItemStack> removed = new ArrayList<>();
        Map<Material, Integer> remainingToRemove = new HashMap<>(amounts);
        ItemStack[] contents = inventory.getContents();

        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) continue;

            int remaining = remainingToRemove.getOrDefault(stack.getType(), 0);
            if (remaining <= 0) continue;

            int take = Math.min(stack.getAmount(), remaining);
            ItemStack part = stack.clone();
            part.setAmount(take);
            removed.add(part);

            if (take == stack.getAmount()) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }

            int left = remaining - take;
            if (left <= 0) {
                remainingToRemove.remove(stack.getType());
            } else {
                remainingToRemove.put(stack.getType(), left);
            }

            if (remainingToRemove.isEmpty()) break;
        }

        inventory.setContents(contents);

        Map<Material, MarketSale> marketSales = reserveMarketBatch(amounts);
        if (marketSales.size() != amounts.size()) {
            restoreChestItems(inventory, removed);
            playerLock.unlock();
            lock.unlock();
            return;
        }

        BigDecimal gross = marketSales.values().stream()
                .map(MarketSale::gross)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (gross.compareTo(BigDecimal.valueOf(maxMoney)) > 0) {
            releaseMarketBatch(marketSales);
            restoreChestItems(inventory, removed);
            playerLock.unlock();
            lock.unlock();
            return;
        }

        long payout = afterTax(gross, sellTax);
        if (payout < 1) {
            releaseMarketBatch(marketSales);
            restoreChestItems(inventory, removed);
            playerLock.unlock();
            lock.unlock();
            return;
        }

        String discordId = linkedId(info.owner());
        if (discordId == null) {
            releaseMarketBatch(marketSales);
            restoreChestItems(inventory, removed);
            playerLock.unlock();
            lock.unlock();
            return;
        }

        final long finalPayout = payout;
        final int finalTotalItems = totalItems;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            BankMutationResult result = changeBank(discordId, finalPayout,
                    "CoolWips SMP automatic sell chest", null);
            Bukkit.getScheduler().runTask(this, () -> {
                if (result.state() == BankMutationState.NOT_APPLIED) {
                    releaseMarketBatch(marketSales);
                    restoreChestItems(inventory, removed);
                    playerLock.unlock();
                    lock.unlock();
                    return;
                }

                if (result.state() == BankMutationState.UNKNOWN) {
                    getLogger().severe("Sell chest payout could not be verified for " + info.owner() +
                            ". Items were not automatically restored to prevent a duplicate sale.");
                    playerLock.unlock();
                    lock.unlock();
                    return;
                }
                for (Map.Entry<Material, Integer> entry : amounts.entrySet()) {
                    MarketSale sale = marketSales.get(entry.getKey());
                    long itemPayout = afterTax(sale.gross(), sellTax);
                    record(new Transaction(info.owner(), Bukkit.getOfflinePlayer(info.owner()).getName(),
                            entry.getKey().name(), entry.getValue(), itemPayout, false, new java.util.Date().toString()));
                }
                automaticSellChestCooldowns.put(info.key(), System.currentTimeMillis());
                Player online = Bukkit.getPlayer(info.owner());
                if (online != null) {
                    online.sendMessage("§aSell chest automatically sold §f" + finalTotalItems + " items §afor §a$" + money(finalPayout) + "§a.");
                }
                playerLock.unlock();
                lock.unlock();

                // If the chest still has sellable items (for example, because it held
                // more than maxItems), automatically process the remainder after cooldown.
                Bukkit.getScheduler().runTask(this, () -> scheduleAutomaticSellChest(inventory));
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

        BigDecimal unit = currentSellPrice(material);
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

        BigDecimal gross = unit.multiply(BigDecimal.valueOf(amount));
        if (gross.compareTo(BigDecimal.valueOf(maxMoney)) > 0) {
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

        final int finalAmount = amount;
        final Material finalMaterial = material;
        final Location returnLocation = p.getLocation().clone();

        List<ItemStack> removed = new ArrayList<>();
        MarketSale marketSale = null;
        try {
            // Remove the items before issuing the money credit. The sale has a complete
            // rollback path, so failures before a confirmed payout return the items.
            List<ItemStack> takenItems = takeItems(p, finalMaterial, finalAmount);
            removed.addAll(takenItems);
            if (takenItems.stream().mapToInt(ItemStack::getAmount).sum() != finalAmount) {
                restoreItems(p, takenItems);
                lock.unlock();
                p.sendMessage("§cSale cancelled because the item transfer could not be completed.");
                return;
            }

            marketSale = reserveMarketSale(finalMaterial, finalAmount);
            if (marketSale == null) {
                restoreItems(p, removed);
                lock.unlock();
                p.sendMessage("§cSale cancelled because the market price could not be reserved.");
                return;
            }

            final long finalMoney = afterTax(marketSale.gross(), sellTax);
            if (marketSale.gross().compareTo(BigDecimal.valueOf(maxMoney)) > 0 || finalMoney < 1) {
                releaseMarketSale(marketSale);
                restoreItems(p, removed);
                lock.unlock();
                p.sendMessage("§cSale cancelled because the current market price exceeds the transaction limits.");
                return;
            }

            p.sendMessage("§7Selling §f" + finalAmount + "x " + pretty(finalMaterial)
                    + " §7for §a$" + money(finalMoney) + " §7after tax...");

            final MarketSale finalMarketSale = marketSale;
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                BankMutationResult mutation;
                try {
                    Long before = bankBalance(discordId);
                    if (before == null) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            failSellTransaction(p, returnLocation, removed, finalMarketSale);
                            if (lock.isHeldByCurrentThread()) lock.unlock();
                            if (p.isOnline()) p.sendMessage("§cSale cancelled. Your balance could not be verified.");
                        });
                        return;
                    }

                    mutation = changeBank(discordId, finalMoney, reason, before);
                } catch (RuntimeException e) {
                    getLogger().log(java.util.logging.Level.SEVERE,
                            "Unexpected exception while processing a sale for " + p.getName() + ".", e);
                    Bukkit.getScheduler().runTask(this, () -> {
                        failSellTransaction(p, returnLocation, removed, finalMarketSale);
                        if (lock.isHeldByCurrentThread()) lock.unlock();
                        if (p.isOnline()) p.sendMessage("§cSale cancelled because an internal error occurred. Your items were returned.");
                    });
                    return;
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        if (mutation.state() == BankMutationState.NOT_APPLIED) {
                            failSellTransaction(p, returnLocation, removed, finalMarketSale);
                            lock.unlock();
                            if (p.isOnline()) p.sendMessage("§cSale cancelled. No money was added; your items were returned.");
                            return;
                        }

                        if (mutation.state() == BankMutationState.UNKNOWN) {
                            lock.unlock();
                            if (p.isOnline()) p.sendMessage("§cSale could not be verified. Do not retry; contact staff.");
                            return;
                        }

                        record(new Transaction(p.getUniqueId(), p.getName(), finalMaterial.name(),
                                finalAmount, finalMoney, false, new java.util.Date().toString()));
                        if (p.isOnline()) {
                            p.sendMessage("§aSold §f" + finalAmount + "x " + pretty(finalMaterial)
                                    + " §afor §a$" + money(finalMoney) + "§a.");
                        }
                        lock.unlock();
                    } catch (RuntimeException e) {
                        getLogger().log(java.util.logging.Level.SEVERE,
                                "Unexpected exception while finishing a sale for " + p.getName()
                                        + " (bank state " + mutation.state() + ").", e);

                        // Never restore items after an uncertain/applied bank mutation:
                        // doing so could duplicate a successful payout.
                        if (mutation.state() == BankMutationState.NOT_APPLIED) {
                            failSellTransaction(p, returnLocation, removed, finalMarketSale);
                        }
                        if (lock.isHeldByCurrentThread()) lock.unlock();

                        if (p.isOnline()) {
                            p.sendMessage(mutation.state() == BankMutationState.NOT_APPLIED
                                    ? "§cSale failed safely. Your items were returned."
                                    : "§cSale completed, but the confirmation message failed. Check your balance before retrying.");
                        }
                    }
                });
            });
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Unexpected exception while preparing a sale for " + p.getName() + ".", e);
            if (marketSale != null) {
                releaseMarketSale(marketSale);
            }
            if (!removed.isEmpty()) {
                restoreItems(p, removed);
            }
            if (lock.isHeldByCurrentThread()) lock.unlock();
            p.sendMessage("§cSale cancelled because an internal error occurred. Your items were returned.");
        }
    }

    private void failSellTransaction(Player p, Location returnLocation, List<ItemStack> removed, MarketSale marketSale) {
        try {
            if (marketSale != null) releaseMarketSale(marketSale);
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not release market reservation during sale rollback.", e);
        }

        try {
            if (p.isOnline()) {
                restoreItems(p, removed);
            } else if (returnLocation != null && returnLocation.getWorld() != null) {
                for (ItemStack item : removed) {
                    returnLocation.getWorld().dropItemNaturally(returnLocation, item);
                }
            }
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not restore items during sale rollback.", e);
            if (!p.isOnline() && returnLocation != null && returnLocation.getWorld() != null) {
                for (ItemStack item : removed) {
                    try {
                        returnLocation.getWorld().dropItemNaturally(returnLocation, item);
                    } catch (RuntimeException ignored) {
                        getLogger().severe("Emergency item restoration also failed for " + p.getName() + ".");
                    }
                }
            }
        }
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
        BigDecimal unit = material == null ? null : prices.get(material);
        if (unit == null) {
            buyer.sendMessage("§cThat item is not in the sell-price list.");
            return;
        }
        if (amount > maxItems) {
            buyer.sendMessage("§cYou can trade at most " + maxItems + " items at once.");
            return;
        }

        BigDecimal totalValue = unit.multiply(BigDecimal.valueOf(amount));
        long total;
        try {
            total = totalValue.setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException e) {
            buyer.sendMessage("§cThat trade is too large.");
            return;
        }
        if (total > maxMoney) {
            buyer.sendMessage("§cThat trade exceeds the $"+money(maxMoney)+" transaction limit.");
            return;
        }
        List<ItemStack> preview = previewItems(seller, material, amount);
        if (preview.stream().mapToInt(ItemStack::getAmount).sum() != amount) {
            buyer.sendMessage("§c" + seller.getName() + " does not have " + amount + "x " + pretty(material) + ".");
            return;
        }
        if (!canFitItems(buyer, preview)) {
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

    private void payCash(Player sender, String targetName, long amount) {
        if (amount < 1) {
            sender.sendMessage("§cPayment amount must be at least $1.");
            return;
        }
        if (amount > maxMoney) {
            sender.sendMessage("§cYou can pay at most $" + money(maxMoney) + " at once.");
            return;
        }
        if (!cooldownReady(sender)) return;

        Player recipient = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getName().equalsIgnoreCase(targetName))
                .findFirst()
                .orElse(null);

        if (recipient == null) {
            sender.sendMessage("§cThat player must be online.");
            return;
        }
        if (recipient.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage("§cYou cannot pay yourself.");
            return;
        }

        String senderDiscord = linkedId(sender);
        if (senderDiscord == null) return;
        String recipientDiscord = linkedId(recipient);
        if (recipientDiscord == null) {
            sender.sendMessage("§cThat player must link their Minecraft account with DiscordSRV first.");
            return;
        }

        ReentrantLock senderLock = locks.computeIfAbsent(sender.getUniqueId(), k -> new ReentrantLock());
        ReentrantLock recipientLock = locks.computeIfAbsent(recipient.getUniqueId(), k -> new ReentrantLock());

        ReentrantLock first = sender.getUniqueId().toString().compareTo(recipient.getUniqueId().toString()) < 0
                ? senderLock : recipientLock;
        ReentrantLock second = first == senderLock ? recipientLock : senderLock;

        if (!first.tryLock()) {
            sender.sendMessage("§eYou or the other player currently has another transaction processing.");
            return;
        }
        if (!second.tryLock()) {
            first.unlock();
            sender.sendMessage("§eYou or the other player currently has another transaction processing.");
            return;
        }

        sender.sendMessage("§7Sending §a$" + money(amount) + " §7to §f" + recipient.getName() + "§7...");

        final long finalAmount = amount;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Long balance = bankBalance(senderDiscord);
            if (balance == null) {
                unlockOnMainThread(first, second);
                Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§cPayment cancelled. I could not verify your balance."));
                return;
            }

            if (balance < 0 || balance < finalAmount) {
                unlockOnMainThread(first, second);
                Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§cPayment cancelled. You do not have enough money; your balance cannot go below $0."));
                return;
            }

            BankMutationResult debit = changeBank(senderDiscord, -finalAmount,
                    "CoolWips SMP player payment to " + recipient.getName(), balance);

            if (debit.state() == BankMutationState.NOT_APPLIED) {
                unlockOnMainThread(first, second);
                Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§cPayment cancelled. No money was removed."));
                return;
            }

            if (debit.state() == BankMutationState.UNKNOWN) {
                unlockOnMainThread(first, second);
                Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§cPayment could not be verified. Do not retry; contact staff."));
                return;
            }

            Long remainingBalance = debit.balance();
            if (remainingBalance == null || remainingBalance < 0) {
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage("§cPayment cancelled because your balance could not be verified safely. Reversal required.");
                    reverseMoney(senderDiscord, finalAmount,
                            "CoolWips SMP player payment negative-balance safeguard",
                            sender, first, second);
                });
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                BankMutationResult credit = changeBank(recipientDiscord, finalAmount,
                        "CoolWips SMP player payment from " + sender.getName(), null);

                if (credit.state() == BankMutationState.NOT_APPLIED) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage("§cPayment failed while crediting the recipient. Your payment is being reversed.");
                        recipient.sendMessage("§cA payment from §f" + sender.getName() + "§c could not be completed.");
                        reverseMoney(senderDiscord, finalAmount,
                                "CoolWips SMP player payment reversal",
                                sender, first, second);
                    });
                    return;
                }

                if (credit.state() == BankMutationState.UNKNOWN) {
                    unlockOnMainThread(first, second);
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage("§cPayment status could not be verified. Do not retry; contact staff.");
                        recipient.sendMessage("§cA payment from §f" + sender.getName() + "§c has an uncertain status. Contact staff.");
                    });
                    return;
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage("§aSent §f$" + money(finalAmount) + " §ato §f" + recipient.getName() + "§a.");
                    recipient.sendMessage("§aReceived §f$" + money(finalAmount) + " §afrom §f" + sender.getName() + "§a.");
                    first.unlock();
                    second.unlock();
                });
            });
        });
    }

    private void postBounty(Player poster, String targetName, long amount) {
        Player target = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getName().equalsIgnoreCase(targetName))
                .findFirst()
                .orElse(null);

        if (target == null) {
            poster.sendMessage("§cThat player must be online to place a bounty.");
            return;
        }
        if (target.getUniqueId().equals(poster.getUniqueId())) {
            poster.sendMessage("§cYou cannot place a bounty on yourself.");
            return;
        }

        if (!cooldownReady(poster)) return;

        String posterDiscord = linkedId(poster);
        if (posterDiscord == null) return;

        ReentrantLock lock = locks.computeIfAbsent(poster.getUniqueId(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            poster.sendMessage("§eYou already have a transaction processing. Please wait.");
            return;
        }

        final UUID targetUuid = target.getUniqueId();
        final String finalTargetName = target.getName();
        final long finalAmount = amount;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Long balance = bankBalance(posterDiscord);
            if (balance == null) {
                unlockOnMainThread(lock);
                Bukkit.getScheduler().runTask(this, () ->
                        poster.sendMessage("§cBounty cancelled. I could not verify your balance."));
                return;
            }
            if (balance < 0 || balance < finalAmount) {
                unlockOnMainThread(lock);
                Bukkit.getScheduler().runTask(this, () ->
                        poster.sendMessage("§cBounty cancelled. You do not have enough money; your balance cannot go below $0."));
                return;
            }

            BankMutationResult debit = changeBank(posterDiscord, -finalAmount,
                    "CoolWips SMP bounty placed on " + finalTargetName, balance);

            if (debit.state() == BankMutationState.NOT_APPLIED) {
                unlockOnMainThread(lock);
                Bukkit.getScheduler().runTask(this, () ->
                        poster.sendMessage("§cBounty cancelled. No money was removed."));
                return;
            }

            if (debit.state() == BankMutationState.UNKNOWN) {
                unlockOnMainThread(lock);
                Bukkit.getScheduler().runTask(this, () ->
                        poster.sendMessage("§cBounty could not be verified. Do not retry; contact staff."));
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                bountyStateLock.lock();
                try {
                    Bounty existing = bounties.get(targetUuid);
                    long newTotal;
                    try {
                        newTotal = Math.addExact(existing == null ? 0L : existing.amount(), finalAmount);
                    } catch (ArithmeticException e) {
                        reverseMoney(posterDiscord, finalAmount, "CoolWips SMP bounty overflow reversal", poster, lock);
                        poster.sendMessage("§cBounty cancelled because the target's bounty is too large.");
                        return;
                    }

                    if (newTotal > maximumBounty) {
                        reverseMoney(posterDiscord, finalAmount, "CoolWips SMP bounty limit reversal", poster, lock);
                        poster.sendMessage("§cThat bounty would exceed the $" + money(maximumBounty) + " maximum.");
                        return;
                    }

                    bounties.put(targetUuid, new Bounty(finalTargetName, newTotal));
                    try {
                        saveBounties();
                    } catch (Exception e) {
                        Bounty previous = existing;
                        if (previous == null) bounties.remove(targetUuid);
                        else bounties.put(targetUuid, previous);
                        reverseMoney(posterDiscord, finalAmount, "CoolWips SMP bounty save failure reversal", poster, lock);
                        poster.sendMessage("§cBounty cancelled because it could not be saved.");
                        return;
                    }

                    poster.sendMessage("§aBounty of §f$" + money(finalAmount) + " §aplaced on §f" +
                            finalTargetName + "§a. Total bounty: §e$" + money(newTotal) + "§a.");
                    target.sendMessage("§cA §6$" + money(finalAmount) + " §cbounty was placed on you. Total bounty: §6$" +
                            money(newTotal) + "§c.");
                    Bukkit.broadcastMessage("§6§lBOUNTY §f" + poster.getName() + " §7placed a §a$" +
                            money(finalAmount) + " §7bounty on §c" + finalTargetName +
                            "§7. Total: §a$" + money(newTotal) + "§7.");
                } finally {
                    bountyStateLock.unlock();
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                }
            });
        });
    }

    private void listBounties(Player p) {
        List<Bounty> list;
        bountyStateLock.lock();
        try {
            list = bounties.values().stream()
                    .sorted(Comparator.comparingLong(Bounty::amount).reversed()
                            .thenComparing(Bounty::targetName, String.CASE_INSENSITIVE_ORDER))
                    .limit(15)
                    .toList();
        } finally {
            bountyStateLock.unlock();
        }

        p.sendMessage("§6§lCoolWips Bounties");
        if (list.isEmpty()) {
            p.sendMessage("§7There are currently no active bounties.");
            return;
        }
        for (Bounty bounty : list) {
            p.sendMessage("§f" + bounty.targetName() + " §7- §a$" + money(bounty.amount()));
        }
    }

    private void loadBounties() {
        bountyStateLock.lock();
        try {
            bounties.clear();
            var section = getConfig().getConfigurationSection("bounties");
            if (section == null) return;

            for (String key : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    long amount = section.getLong(key + ".amount", 0L);
                    String name = section.getString(key + ".target-name", "");
                    if (amount > 0 && !name.isBlank() && amount <= maximumBounty) {
                        bounties.put(uuid, new Bounty(name, amount));
                    }
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Ignoring invalid bounty UUID: " + key);
                }
            }
        } finally {
            bountyStateLock.unlock();
        }
    }

    private void saveBounties() {
        getConfig().set("bounties", null);
        for (Map.Entry<UUID, Bounty> entry : bounties.entrySet()) {
            getConfig().set("bounties." + entry.getKey() + ".target-name", entry.getValue().targetName());
            getConfig().set("bounties." + entry.getKey() + ".amount", entry.getValue().amount());
        }
        saveConfig();
    }

    private long parseMoneyAmount(Player p, String text, long min, long max) {
        try {
            long amount = Long.parseLong(text);
            if (amount < min || amount > max) {
                p.sendMessage("§cAmount must be between $" + money(min) + " and $" + money(max) + ".");
                return -1;
            }
            return amount;
        } catch (NumberFormatException e) {
            p.sendMessage("§cAmount must be a whole number.");
            return -1;
        }
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

        List<ItemStack> preview = previewItems(seller, material, trade.amount());
        if (preview.stream().mapToInt(ItemStack::getAmount).sum() != trade.amount()) {
            removeTrade(trade);
            seller.sendMessage("§cYou no longer have enough of the requested item.");
            buyer.sendMessage("§cThe trade was cancelled because the seller no longer has enough items.");
            return;
        }
        if (!canFitItems(buyer, preview)) {
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
            Long balance = bankBalance(buyerDiscord);
            if (balance == null) {
                unlockOnMainThread(first, second);
                Bukkit.getScheduler().runTask(this, () -> {
                    buyer.sendMessage("§cTrade cancelled. I could not verify the buyer's balance.");
                    seller.sendMessage("§cTrade cancelled because the buyer's balance could not be verified.");
                });
                return;
            }
            if (balance < 0 || balance < total) {
                unlockOnMainThread(first, second);
                Bukkit.getScheduler().runTask(this, () -> {
                    buyer.sendMessage("§cTrade cancelled. The buyer does not have enough money; their balance cannot go below $0.");
                    seller.sendMessage("§cTrade cancelled because the buyer does not have enough money.");
                });
                return;
            }

            BankMutationResult debit = changeBank(buyerDiscord, -total,
                    "Player item trade purchase", balance);

            if (debit.state() == BankMutationState.NOT_APPLIED) {
                unlockOnMainThread(first, second);
                Bukkit.getScheduler().runTask(this, () -> {
                    buyer.sendMessage("§cTrade cancelled. No money was removed.");
                    seller.sendMessage("§cTrade cancelled because the buyer payment did not apply.");
                });
                return;
            }

            if (debit.state() == BankMutationState.UNKNOWN) {
                unlockOnMainThread(first, second);
                Bukkit.getScheduler().runTask(this, () -> {
                    buyer.sendMessage("§cTrade payment could not be verified. Do not retry; contact staff.");
                    seller.sendMessage("§cTrade payment status could not be verified. Contact staff.");
                });
                return;
            }

            Long remainingTradeBalance = debit.balance();
            if (remainingTradeBalance == null || remainingTradeBalance < 0) {
                Bukkit.getScheduler().runTask(this, () -> {
                    buyer.sendMessage("§cTrade cancelled because your balance could not be verified safely. Reversal required.");
                    seller.sendMessage("§cTrade cancelled because the buyer balance could not be verified.");
                    reverseMoney(buyerDiscord, total, "Player item trade negative-balance safeguard", null, first, second);
                });
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                List<ItemStack> currentItems = previewItems(seller, material, trade.amount());
                if (!buyer.isOnline() || !seller.isOnline()
                        || currentItems.stream().mapToInt(ItemStack::getAmount).sum() != trade.amount()
                        || !canFitItems(buyer, currentItems)) {
                    reverseMoney(buyerDiscord, total, "Player item trade reversal", null, first, second);
                    buyer.sendMessage("§cTrade cancelled. Conditions changed, so your payment is being reversed.");
                    seller.sendMessage("§cTrade cancelled. Conditions changed, so the payment is being reversed.");
                    return;
                }

                final List<ItemStack> tradedItems = takeItems(seller, material, trade.amount());
                if (tradedItems.stream().mapToInt(ItemStack::getAmount).sum() != trade.amount()) {
                    restoreItems(seller, tradedItems);
                    reverseMoney(buyerDiscord, total, "Player item trade reversal", null, first, second);
                    buyer.sendMessage("§cTrade cancelled. Your payment is being reversed.");
                    seller.sendMessage("§cTrade cancelled because the item transfer could not be completed.");
                    return;
                }

                if (!addItemsAtomically(buyer, tradedItems)) {
                    restoreItems(seller, tradedItems);
                    reverseMoney(buyerDiscord, total, "Player item trade reversal", null, first, second);
                    buyer.sendMessage("§cTrade cancelled. Your payment is being reversed.");
                    seller.sendMessage("§cTrade cancelled because the item could not be delivered.");
                    return;
                }

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    BankMutationResult credit = changeBank(sellerDiscord, total,
                            "Player item trade sale", null);

                    if (credit.state() == BankMutationState.NOT_APPLIED) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            restoreItems(seller, tradedItems);
                            buyer.sendMessage("§cSeller payment failed. Your payment is being reversed.");
                            seller.sendMessage("§cTrade payment failed. The items were returned.");
                            reverseMoney(buyerDiscord, total, "Player item trade reversal", null, first, second);
                        });
                        return;
                    }

                    if (credit.state() == BankMutationState.UNKNOWN) {
                        unlockOnMainThread(first, second);
                        Bukkit.getScheduler().runTask(this, () -> {
                            buyer.sendMessage("§cSeller payment status could not be verified. Do not retry; contact staff.");
                            seller.sendMessage("§cTrade payment status could not be verified. Contact staff.");
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
            Long balance = bankBalance(discordId);
            if (balance == null) {
                unlockOnMainThread(lock);
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage("§cPurchase cancelled. I could not verify your balance."));
                return;
            }
            if (balance < 0 || balance < money) {
                unlockOnMainThread(lock);
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage("§cPurchase cancelled. You do not have enough money; your balance cannot go below $0."));
                return;
            }

            // UnbelievaBoat treats negative bank as a withdrawal from the user's bank balance.
            BankMutationResult debit = changeBank(discordId, -money, buyReason, balance);

            if (debit.state() == BankMutationState.NOT_APPLIED) {
                unlockOnMainThread(lock);
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage("§cPurchase cancelled. No money was removed."));
                return;
            }

            if (debit.state() == BankMutationState.UNKNOWN) {
                unlockOnMainThread(lock);
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage("§cPurchase could not be verified. Do not retry; contact staff."));
                return;
            }

            Long remainingBalance = debit.balance();
            if (remainingBalance == null || remainingBalance < 0) {
                Bukkit.getScheduler().runTask(this, () -> {
                    p.sendMessage("§cPurchase cancelled because your balance could not be verified safely. Reversal required.");
                    reverseMoney(discordId, money, "Shop purchase negative-balance safeguard", p, lock);
                });
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline() || !canFit(p, finalMaterial, amount)) {
                    p.sendMessage("§cThe item could not be added. Reversing your payment...");
                    reverseMoney(discordId, money, "Shop purchase reversal", p, lock);
                    return;
                }

                if (!addItemsAtomically(p, splitStackItems(finalMaterial, amount))) {
                    p.sendMessage("§cThe item could not be added. Reversing your payment...");
                    reverseMoney(discordId, money, "Shop purchase reversal", p, lock);
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


    private Long bankBalance(String discordId) {
        try {
            HttpResult result = api("GET", userUrl(discordId), null);
            if (!success(result)) return null;
            Matcher match = BANK.matcher(result.body);
            return match.find() ? Long.parseLong(match.group(1)) : null;
        } catch (Exception e) {
            getLogger().warning("Could not read UnbelievaBoat bank balance: " + e.getMessage());
            return null;
        }
    }

    private void startNonNegativeBalanceGuard() {
        final long periodTicks = 20L * 10L;

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Map<UUID, String> accounts = new HashMap<>();

            for (Player player : Bukkit.getOnlinePlayers()) {
                String discordId = linkedId(player.getUniqueId());
                if (discordId != null) {
                    accounts.put(player.getUniqueId(), discordId);
                }
            }

            if (accounts.isEmpty()) return;

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                for (Map.Entry<UUID, String> entry : accounts.entrySet()) {
                    String discordId = entry.getValue();
                    ReentrantLock lock = locks.computeIfAbsent(entry.getKey(), k -> new ReentrantLock());

                    // Never repair a balance while another economy transaction for this
                    // player is in progress, otherwise a failed purchase could be
                    // accidentally credited twice.
                    if (!lock.tryLock()) continue;

                    try {
                        Long balance = bankBalance(discordId);
                        if (balance == null || balance >= 0) continue;

                        if (balance < Integer.MIN_VALUE) {
                            getLogger().severe("Negative UnbelievaBoat balance for " + discordId
                                    + " is below the API integer range; automatic repair failed.");
                            continue;
                        }

                        long repair = -balance;
                        HttpResult result = api("PATCH", userUrl(discordId),
                                "{\"bank\":" + repair + ",\"reason\":\"CoolWips SMP negative-balance safeguard\"}");

                        Long repairedBalance = parseBank(result.body);
                        if (success(result) && repairedBalance != null && repairedBalance >= 0) {
                            getLogger().warning("Repaired negative UnbelievaBoat bank balance for "
                                    + discordId + " from $" + balance + " to $" + repairedBalance + ".");
                            UUID uuid = entry.getKey();
                            Bukkit.getScheduler().runTask(this, () -> {
                                Player player = Bukkit.getPlayer(uuid);
                                if (player != null && player.isOnline()) {
                                    player.sendMessage("§eYour negative economy balance was automatically corrected to $0.");
                                }
                            });
                        } else {
                            getLogger().severe("Could not repair negative UnbelievaBoat balance for "
                                    + discordId + ". HTTP " + result.status + ".");
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            });
        }, periodTicks, periodTicks);
    }

    private enum BankMutationState {
        APPLIED, NOT_APPLIED, UNKNOWN
    }

    private record BankMutationResult(BankMutationState state, Long balance) {}

    private BankMutationResult changeBank(String discordId, long delta, String mutationReason,
                                          Long expectedBefore) {
        Long before = expectedBefore == null ? bankBalance(discordId) : expectedBefore;
        if (before == null) return new BankMutationResult(BankMutationState.UNKNOWN, null);

        long expectedAfter;
        try {
            expectedAfter = Math.addExact(before, delta);
        } catch (ArithmeticException e) {
            getLogger().severe("Rejected bank mutation because the expected balance would overflow for " + discordId + ".");
            return new BankMutationResult(BankMutationState.UNKNOWN, before);
        }

        HttpResult result = api("PATCH", userUrl(discordId),
                "{\"bank\":" + delta + ",\"reason\":\"" + json(mutationReason) + "\"}");

        Long reported = parseBank(result.body);
        if (success(result) && reported != null) {
            if (reported == expectedAfter) return new BankMutationResult(BankMutationState.APPLIED, reported);
            if (reported == before) return new BankMutationResult(BankMutationState.NOT_APPLIED, reported);
        }

        // A timeout or non-2xx response does NOT prove that the PATCH was rejected.
        // Verify the account before deciding whether to reverse, restore, or retry.
        Long verified = bankBalance(discordId);
        if (verified != null) {
            if (verified == expectedAfter) return new BankMutationResult(BankMutationState.APPLIED, verified);
            if (verified == before) return new BankMutationResult(BankMutationState.NOT_APPLIED, verified);
        }

        getLogger().severe("Bank mutation could not be verified for " + discordId
                + " (delta " + delta + ", HTTP " + result.status + "). No automatic retry or reversal will be attempted.");
        return new BankMutationResult(BankMutationState.UNKNOWN, verified);
    }

    private void reverseMoney(String discordId, long money, String reversalReason,
                              Player p, ReentrantLock... locksToUnlock) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Long before = bankBalance(discordId);
            if (before == null) {
                getLogger().severe("Could not verify the balance before reversing $" + money + " for "
                        + (p == null ? "an economy transaction" : p.getName()) + ". No reversal was attempted.");
                unlockOnMainThread(locksToUnlock);
                return;
            }

            BankMutationResult reversal = changeBank(discordId, money, reversalReason, before);
            if (reversal.state() == BankMutationState.UNKNOWN) {
                getLogger().severe("Reversal of $" + money + " could not be verified for "
                        + (p == null ? "an economy transaction" : p.getName())
                        + ". No automatic retry will be attempted.");
            }
            unlockOnMainThread(locksToUnlock);
        });
    }

    private void unlockOnMainThread(ReentrantLock... locksToUnlock) {
        if (locksToUnlock == null || locksToUnlock.length == 0) return;

        Runnable unlockTask = () -> {
            for (ReentrantLock lock : locksToUnlock) {
                if (lock != null && lock.isHeldByCurrentThread()) lock.unlock();
            }
        };

        if (Bukkit.isPrimaryThread()) unlockTask.run();
        else Bukkit.getScheduler().runTask(this, unlockTask);
    }

    private boolean canFit(Player p, Material material, int amount) {
        return canFitItems(p, splitStackItems(material, amount));
    }

    private boolean canFitItems(Player p, List<ItemStack> items) {
        int size = p.getInventory().getStorageContents().length;
        Inventory simulation = Bukkit.createInventory(null, size);
        simulation.setContents(cloneContents(p.getInventory().getStorageContents()));

        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            if (!simulation.addItem(item.clone()).isEmpty()) return false;
        }
        return true;
    }

    private List<ItemStack> splitStackItems(Material material, int amount) {
        List<ItemStack> items = new ArrayList<>();
        int maxStack = new ItemStack(material).getMaxStackSize();
        int left = amount;
        while (left > 0) {
            int take = Math.min(left, maxStack);
            items.add(new ItemStack(material, take));
            left -= take;
        }
        return items;
    }

    private List<ItemStack> previewItems(Player p, Material material, int amount) {
        List<ItemStack> items = new ArrayList<>();
        int left = amount;
        for (ItemStack stack : p.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(left, stack.getAmount());
            ItemStack part = stack.clone();
            part.setAmount(take);
            items.add(part);
            left -= take;
            if (left <= 0) break;
        }
        return items;
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private boolean addItemsAtomically(Player p, List<ItemStack> items) {
        ItemStack[] before = cloneContents(p.getInventory().getStorageContents());
        if (giveItems(p, items).isEmpty()) return true;
        p.getInventory().setStorageContents(before);
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

    private long afterTax(BigDecimal gross, double tax) {
        BigDecimal multiplier = BigDecimal.ONE.subtract(BigDecimal.valueOf(tax));
        return Math.max(0, gross.multiply(multiplier).setScale(0, RoundingMode.HALF_UP).longValue());
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
    private record Bounty(String targetName, long amount) {}
    private record PendingTrade(UUID buyerUuid, UUID sellerUuid, String sellerName, String material, int amount,
                                long total, long expiresAt) {}
    private record Transaction(UUID uuid, String player, String material, int amount, long money,
                               boolean purchase, String timestamp) {}

    private boolean success(HttpResult result) {
        return result.status >= 200 && result.status < 300;
    }

    private Long parseBank(String body) {
        try {
            Matcher match = BANK.matcher(body == null ? "" : body);
            return match.find() ? Long.parseLong(match.group(1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String linkedId(UUID uuid) {
        try {
            String id = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(uuid);
            return (id == null || id.isBlank()) ? null : id;
        } catch (Exception e) {
            return null;
        }
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
            Matcher match = BANK.matcher(result.body);

            if (success(result) && match.find()) {
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage("§aUnbelievaBoat bank: §f$" + match.group(1)));
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
        sender.sendMessage("§7Dynamic renewable market: §aenabled");
        sender.sendMessage("§7Full-price volume per item: §f" + marketFreeUnits + " units/day");
        sender.sendMessage("§7Price drop per step: §f" + marketDropPercent.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
        sender.sendMessage("§7Minimum market price: §f$" + marketMinPrice.stripTrailingZeros().toPlainString());
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

    private Map<Material, BigDecimal> currentSellPrices() {
        Map<Material, BigDecimal> snapshot = new HashMap<>();
        for (Material material : prices.keySet()) {
            BigDecimal value = currentSellPrice(material);
            if (value != null) snapshot.put(material, value);
        }
        return snapshot;
    }

    private void showPaged(CommandSender sender, Map<Material, ?> map,
                           int page, String title, String command) {
        List<? extends Map.Entry<Material, ?>> list = map.entrySet().stream()
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
            String query = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>(List.of("confirm", "cancel"));
            Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(x -> x.toLowerCase(Locale.ROOT).startsWith(query))
                    .sorted()
                    .limit(48)
                    .forEach(suggestions::add);
            return suggestions;
        }

        if (name.equals("pay") && args.length == 2) {
            String query = args[1].toUpperCase(Locale.ROOT);
            return prices.keySet().stream().map(Enum::name)
                    .filter(x -> x.startsWith(query)).sorted().limit(50).toList();
        }

        if (name.equals("bounty") && args.length == 1) {
            String query = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>(List.of("list"));
            Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(x -> x.toLowerCase(Locale.ROOT).startsWith(query))
                    .sorted()
                    .limit(48)
                    .forEach(suggestions::add);
            return suggestions;
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