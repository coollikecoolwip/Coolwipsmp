package io.github.coollikecoolwip.economy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SellMenuPaymentTest {
    @TempDir Path directory;
    private CoolWipsEconomy plugin;
    private Player player;
    private Inventory inventory;
    private HttpClient http;
    private SellMenu menu;
    private SellMenu.Session session;
    private MockedStatic<Bukkit> bukkit;
    private final AtomicReference<ItemStack[]> contents = new AtomicReference<>(new ItemStack[27]);
    private final Map<Material, BigDecimal> prices = new ConcurrentHashMap<>();
    private final Set<Material> maintenance = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Material, Long>> marketSold = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Material, Long>> marketPeaks = new ConcurrentHashMap<>();
    private final Deque<Runnable> async = new ArrayDeque<>();
    private final Deque<Runnable> main = new ArrayDeque<>();
    private final Deque<Object> history = new ArrayDeque<>();
    private final List<String> patches = new ArrayList<>();
    private final YamlConfiguration stats = new YamlConfiguration();
    private String outcome = "APPLIED";
    private long bank = 100L;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(CoolWipsEconomy.class, CALLS_REAL_METHODS);
        player = mock(Player.class);
        inventory = mock(Inventory.class);
        http = mock(HttpClient.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Seller");
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("coolwipseconomy.sell")).thenReturn(true);
        World world = mock(World.class);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenAnswer(call -> contents.get());
        doAnswer(call -> { contents.set(call.getArgument(0)); return null; })
                .when(inventory).setContents(any(ItemStack[].class));
        doAnswer(call -> { contents.set(new ItemStack[27]); return null; }).when(inventory).clear();
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            ItemStack returned = call.getArgument(0);
            for (int slot = 0; slot < 27; slot++) {
                if (contents.get()[slot] == null) {
                    contents.get()[slot] = returned;
                    return new HashMap<Integer, ItemStack>();
                }
            }
            return Map.of(0, returned);
        });
        doReturn(new YamlConfiguration()).when(plugin).getConfig();
        doReturn(directory.toFile()).when(plugin).getDataFolder();
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        doReturn(true).when(plugin).isEnabled();
        doReturn("seller").when(plugin).linkedId(player);

        set("prices", prices);
        set("pricesReady", new AtomicBoolean(true));
        set("maintenanceBlocks", maintenance);
        set("locks", locks);
        set("history", history);
        set("http", http);
        set("baseUrl", "https://example.invalid");
        set("guildId", "guild");
        set("token", "test-token");
        set("maxItems", 1024);
        set("maxMoney", 1_000_000L);
        set("sellTax", 0.05);
        set("marketLock", new ReentrantLock());
        set("marketDay", LocalDate.now().toString());
        set("marketSoldToday", marketSold);
        set("marketPeakSoldToday", marketPeaks);
        set("marketRules", new ConcurrentHashMap<>());
        set("marketDirty", new AtomicBoolean());
        set("marketSaveQueued", new AtomicBoolean());
        set("economyStatsLock", new ReentrantLock());
        set("economyStatsData", stats);
        set("economyStatsDay", LocalDate.now());
        set("economyStatsDirty", new AtomicBoolean());
        set("economyStatsSaveQueued", new AtomicBoolean());
        for (String field : List.of("anomalySingleSaleLimit", "anomalyPlayerMoneyCreatedLimit", "anomalyPlayerItemsSoldLimit")) {
            set(field, Long.MAX_VALUE);
        }
        prices.put(Material.DIAMOND, new BigDecimal("10"));

        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            int status = 200;
            if (request.method().equals("PATCH")) {
                HttpResponse.BodySubscriber<String> body = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
                request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                    public void onSubscribe(Flow.Subscription subscription) { body.onSubscribe(subscription); }
                    public void onNext(ByteBuffer buffer) { body.onNext(List.of(buffer)); }
                    public void onError(Throwable error) { body.onError(error); }
                    public void onComplete() { body.onComplete(); }
                });
                String json = body.getBody().toCompletableFuture().join();
                patches.add(json);
                Matcher delta = Pattern.compile("\\\"bank\\\":(\\d+)").matcher(json);
                assertTrue(delta.find());
                if (!outcome.equals("NOT_APPLIED")) bank += Long.parseLong(delta.group(1));
                if (outcome.equals("UNKNOWN")) status = 503;
            } else if (!patches.isEmpty() && outcome.equals("UNKNOWN")) {
                status = 503;
            }
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            when(response.body()).thenReturn(status == 200 ? "{\"bank\":" + bank + "}" : "unavailable");
            return response;
        });

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            async.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            main.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), anyString()))
                .thenAnswer(call -> { session = call.getArgument(0); return inventory; });
        menu = new SellMenu(plugin, this::process);
        set("sellMenu", menu);
        menu.open(player);
    }

    @AfterEach void closeStaticMock() { if (bukkit != null) bukkit.close(); }

    @Test
    void unpricedAndMaintenanceBlockedItemsAreNeverRemovedOrPaid() {
        maintenance.add(Material.DIAMOND);
        contents.get()[0] = item(Material.DIAMOND, 3);
        contents.get()[1] = item(Material.STONE, 8);

        process(session);

        assertEquals(11, count());
        assertFalse(session.processing);
        assertTrue(async.isEmpty());
        verify(inventory, never()).setContents(any(ItemStack[].class));
        verifyNoInteractions(http);
    }

    @Test
    void maximumMoneyRejectsBatchBeforeRemovingItems() throws Exception {
        set("maxMoney", 29L);
        contents.get()[0] = item(Material.DIAMOND, 3);

        process(session);

        assertEquals(3, count());
        assertFalse(session.processing);
        assertTrue(async.isEmpty());
        assertTrue(recoveryFiles().isEmpty());
        assertUnlocked();
    }

    @Test
    void zeroItemLimitNeverReservesOrRemovesAnything() throws Exception {
        set("maxItems", 0);
        contents.get()[0] = item(Material.DIAMOND, 3);

        process(session);

        assertEquals(3, count());
        verify(inventory, never()).setContents(any(ItemStack[].class));
        verifyNoInteractions(http);
        assertTrue(async.isEmpty());
    }

    @Test
    void subDollarPostTaxValueStaysInTheMenu() throws Exception {
        prices.put(Material.DIAMOND, BigDecimal.ONE);
        contents.get()[0] = item(Material.DIAMOND, 1);

        process(session);

        assertEquals(1, count());
        assertTrue(async.isEmpty());
        assertTrue(recoveryFiles().isEmpty());
        assertUnlocked();
    }

    @Test
    void appliedPaymentCreditsOnceConsumesOnlyItemLimitAndDoesNotReenter() throws Exception {
        set("maxItems", 2);
        contents.get()[0] = item(Material.DIAMOND, 5);

        process(session);
        assertEquals(3, count());
        assertTrue(session.processing);
        process(session);
        assertEquals(1, async.size(), "Repeated menu events must not start another payout");
        async.removeFirst().run();
        main.removeFirst().run();

        assertEquals(119, bank);
        assertEquals(1, patches.size());
        assertEquals(3, count());
        assertFalse(session.processing);
        assertEquals(19, stats.getLong("money_created"));
        assertEquals(2, stats.getLong("items_sold"));
        assertTrue(recoveryFiles().isEmpty());
        assertUnlocked();
    }

    @Test
    void confirmedNonPaymentRestoresExactEscrowAndDeletesRecovery() throws Exception {
        outcome = "NOT_APPLIED";
        contents.get()[0] = item(Material.DIAMOND, 3);

        process(session);
        assertEquals(0, count());
        async.removeFirst().run();
        main.removeFirst().run();

        assertEquals(100, bank);
        assertEquals(3, count());
        assertEquals(1, patches.size());
        assertTrue(history.isEmpty());
        assertTrue(recoveryFiles().isEmpty());
        assertFalse(session.processing);
        assertUnlocked();
    }

    @Test
    void unknownPaymentRetainsRecoveryAndNeverReturnsPossiblyPaidItems() throws Exception {
        outcome = "UNKNOWN";
        contents.get()[0] = item(Material.DIAMOND, 3);

        process(session);
        async.removeFirst().run();
        main.removeFirst().run();

        assertEquals(128, bank, "The simulated timeout happened after the bank accepted payment");
        assertEquals(0, count());
        assertEquals(1, patches.size());
        assertTrue(history.isEmpty());
        assertEquals(1, recoveryFiles().size());
        String journal = Files.readString(recoveryFiles().getFirst());
        assertTrue(journal.contains("bank-result: UNKNOWN"));
        assertTrue(journal.contains("DIAMOND"));
        assertFalse(session.processing);
        assertUnlocked();
    }

    @Test
    void recoveryExistsBeforeQueuedWorkerCanBeCancelledByShutdown() throws Exception {
        contents.get()[0] = item(Material.DIAMOND, 3);

        process(session);
        menu.shutdown(); // The async payment worker has not started.

        assertEquals(0, count());
        assertEquals(1, async.size());
        assertEquals(1, recoveryFiles().size());
        String journal = Files.readString(recoveryFiles().getFirst());
        assertTrue(journal.contains(player.getUniqueId().toString()));
        assertTrue(journal.contains("payout: 28"));
        assertTrue(journal.contains("DIAMOND"));
        verifyNoInteractions(http);
    }

    @Test
    void failedTrackedSaleReleasesReservedVolumeWhileKeepingConservativePricePeak() throws Exception {
        prices.put(Material.IRON_INGOT, new BigDecimal("10"));
        contents.get()[0] = item(Material.IRON_INGOT, 2);
        outcome = "NOT_APPLIED";

        process(session);
        assertEquals(18L, marketSold.get(player.getUniqueId()).get(Material.IRON_INGOT));
        assertEquals(18L, marketPeaks.get(player.getUniqueId()).get(Material.IRON_INGOT));
        async.removeFirst().run();
        main.removeFirst().run();

        assertFalse(marketSold.containsKey(player.getUniqueId()));
        assertEquals(18L, marketPeaks.get(player.getUniqueId()).get(Material.IRON_INGOT));
        assertEquals(2, count());
        assertEquals(100, bank);
        assertUnlocked();
    }

    @Test
    void mixedFractionalBasketRoundsTaxOnceAndLedgerEqualsBankCredit() throws Exception {
        prices.put(Material.DIAMOND, new BigDecimal("1.25"));
        prices.put(Material.STONE, new BigDecimal("2.65"));
        contents.get()[0] = item(Material.DIAMOND, 3);
        contents.get()[1] = item(Material.STONE, 1);

        process(session);
        async.removeFirst().run();
        main.removeFirst().run();

        assertEquals(106, bank); // floor((3 * 1.25 + 2.65) * 0.95)
        assertEquals(2, history.size());
        long recorded = 0;
        for (Object transaction : history) {
            Method money = transaction.getClass().getDeclaredMethod("money");
            money.setAccessible(true);
            recorded += (long) money.invoke(transaction);
        }
        assertEquals(bank - 100L, recorded);
        assertEquals(recorded, stats.getLong("money_created"));
        assertEquals(4, stats.getLong("items_sold"));
        assertEquals(0, count());
        assertEquals(1, patches.size());
    }

    private void process(SellMenu.Session value) {
        try {
            Method method = CoolWipsEconomy.class.getDeclaredMethod("processSellMenu", SellMenu.Session.class);
            method.setAccessible(true);
            method.invoke(plugin, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private ItemStack item(Material material, int amount) {
        ItemStack item = mock(ItemStack.class);
        int[] current = {amount};
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenAnswer(call -> current[0]);
        doAnswer(call -> { current[0] = call.getArgument(0); return null; }).when(item).setAmount(anyInt());
        when(item.clone()).thenAnswer(call -> item(material, current[0]));
        when(item.serialize()).thenAnswer(call -> Map.of("type", material.name(), "amount", current[0]));
        return item;
    }

    private int count() {
        return Arrays.stream(contents.get()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum();
    }

    private List<Path> recoveryFiles() throws Exception {
        Path records = directory.resolve("pending-menu-sales");
        if (!Files.isDirectory(records)) return List.of();
        try (var paths = Files.list(records)) { return paths.toList(); }
    }

    private void assertUnlocked() { assertTrue(locks.values().stream().noneMatch(ReentrantLock::isLocked)); }

    private void set(String name, Object value) throws Exception {
        Field field = CoolWipsEconomy.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
