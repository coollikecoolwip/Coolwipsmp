package io.github.coollikecoolwip.economy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerTradeTest {
    @TempDir Path directory;

    @Test
    void rejectedSellerCreditReturnsOnlyTheEscrowCopyAndRefundsBuyer() throws Exception {
        exerciseTrade("rejected");
    }

    @Test
    void verifiedSellerCreditDeliversTheEscrowExactlyOnce() throws Exception {
        exerciseTrade("applied");
    }

    @Test
    void unknownSellerCreditDeliversOnceWithoutRefundingThePaidBuyer() throws Exception {
        exerciseTrade("unknown");
    }

    @Test
    void cancelledQueuedSellerWorkerLeavesExactEscrowForStaff() throws Exception {
        exerciseTrade("queued");
    }

    @Test
    void disablingBeforeSellerCallbackPreservesPaymentResultAndEscrow() throws Exception {
        exerciseTrade("disabled");
    }

    private void exerciseTrade(String outcome) throws Exception {
        CoolWipsEconomy plugin = mock(CoolWipsEconomy.class, CALLS_REAL_METHODS);
        doReturn(new YamlConfiguration()).when(plugin).getConfig();
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        doReturn(directory.toFile()).when(plugin).getDataFolder();
        doReturn(!outcome.equals("disabled")).when(plugin).isEnabled();
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        World world = mock(World.class);
        AtomicReference<ItemStack[]> sellerItems = new AtomicReference<>(new ItemStack[]{item(1)});
        AtomicReference<ItemStack[]> buyerItems = new AtomicReference<>(new ItemStack[]{null});
        Player seller = player(sellerId, "Seller", world, sellerItems);
        Player buyer = player(buyerId, "Buyer", world, buyerItems);
        Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
        set(plugin, "locks", locks);
        set(plugin, "history", new ArrayDeque<>());
        set(plugin, "baseUrl", "https://example.invalid");
        set(plugin, "guildId", "guild");
        set(plugin, "token", "test-token");
        Map<UUID, Object> pending = new ConcurrentHashMap<>();
        Class<?> tradeType = Class.forName(CoolWipsEconomy.class.getName() + "$PendingTrade");
        Constructor<?> ctor = tradeType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        pending.put(buyerId, ctor.newInstance(buyerId, sellerId, "Seller", "DIAMOND", 1, 10L,
                System.currentTimeMillis() + 30_000L));
        set(plugin, "pendingTrades", pending);

        HttpClient http = mock(HttpClient.class);
        set(plugin, "http", http);
        Map<String, Long> balances = new HashMap<>(Map.of("buyer", 100L, "seller", 0L));
        boolean[] sellerPatchSent = {false};
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            boolean isBuyer = request.uri().getPath().endsWith("/buyer");
            String account = isBuyer ? "buyer" : "seller";
            boolean patch = request.method().equals("PATCH");
            int status = 200;
            if (patch) {
                if (isBuyer) {
                    // The sole buyer PATCH before a reversal is the initial debit.
                    balances.put(account, balances.get(account) == 100L ? 90L : 100L);
                } else {
                    sellerPatchSent[0] = true;
                    if (!outcome.equals("rejected")) balances.put(account, 10L);
                    if (!outcome.equals("applied")) status = 503;
                }
            } else if (!isBuyer && sellerPatchSent[0] && outcome.equals("unknown")) {
                status = 503;
            }
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            when(response.body()).thenReturn(status == 200 ? "{\"bank\":" + balances.get(account) + "}" : "unavailable");
            return response;
        });

        Deque<Runnable> async = new ArrayDeque<>();
        Deque<Runnable> main = new ArrayDeque<>();
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            async.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            main.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        Inventory simulation = mock(Inventory.class);
        when(simulation.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
        doReturn("buyer").when(plugin).linkedId(buyer);
        doReturn("seller").when(plugin).linkedId(seller);
        doReturn(true).when(plugin).canFitItems(eq(buyer), anyList());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(buyerId)).thenReturn(buyer);
            bukkit.when(() -> Bukkit.createInventory(null, 1)).thenReturn(simulation);
            Method confirm = CoolWipsEconomy.class.getDeclaredMethod("payConfirm", Player.class);
            confirm.setAccessible(true);
            confirm.invoke(plugin, seller);
            async.remove().run(); // Verified buyer debit.
            main.remove().run(); // Remove seller items into escrow.
            assertEquals(0, count(sellerItems.get()));
            assertEquals(0, count(buyerItems.get()), "Buyer must not receive items before seller credit resolves");
            Path recovery;
            try (var files = Files.list(directory.resolve("pending-trades"))) {
                recovery = files.findFirst().orElseThrow();
            }
            String journal = Files.readString(recovery);
            assertTrue(journal.contains("buyer-debit-verified: true"));
            assertTrue(journal.contains("DIAMOND"));
            assertTrue(journal.contains(buyerId.toString()));
            assertTrue(journal.contains(sellerId.toString()));
            if (outcome.equals("queued")) {
                assertEquals(1, async.size());
                assertEquals(90L, balances.get("buyer"));
                assertEquals(0L, balances.get("seller"));
                return;
            }
            async.remove().run(); // Attempt seller payout.
            if (outcome.equals("disabled")) {
                assertTrue(main.isEmpty());
                assertTrue(Files.readString(recovery).contains("seller-credit-result: APPLIED"));
                assertEquals(0, count(buyerItems.get()));
                return;
            }
            main.remove().run(); // Commit delivery or restore seller.
            assertFalse(Files.exists(recovery));
            if (outcome.equals("rejected")) {
                assertEquals(1, count(sellerItems.get()));
                assertEquals(0, count(buyerItems.get()), "A rejected seller payout must not duplicate the item");
                async.remove().run(); // Refund verified buyer debit.
                assertEquals(100L, balances.get("buyer"));
                assertEquals(0L, balances.get("seller"));
            } else {
                assertEquals(0, count(sellerItems.get()));
                assertEquals(1, count(buyerItems.get()));
                assertEquals(90L, balances.get("buyer"));
                assertEquals(10L, balances.get("seller"));
            }
            assertTrue(locks.values().stream().noneMatch(ReentrantLock::isLocked));
        }
    }

    private Player player(UUID id, String name, World world, AtomicReference<ItemStack[]> contents) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenAnswer(call -> contents.get());
        doAnswer(call -> { contents.set(call.getArgument(0)); return null; })
                .when(inventory).setStorageContents(any(ItemStack[].class));
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            ItemStack item = call.getArgument(0);
            contents.set(new ItemStack[]{item});
            return new HashMap<>();
        });
        return player;
    }

    private ItemStack item(int amount) {
        ItemStack item = mock(ItemStack.class);
        int[] current = {amount};
        when(item.getType()).thenReturn(Material.DIAMOND);
        when(item.getAmount()).thenAnswer(call -> current[0]);
        doAnswer(call -> { current[0] = call.getArgument(0); return null; }).when(item).setAmount(anyInt());
        when(item.clone()).thenAnswer(call -> item(current[0]));
        when(item.serialize()).thenAnswer(call -> Map.of("type", "DIAMOND", "amount", current[0]));
        return item;
    }

    private int count(ItemStack[] items) {
        return Arrays.stream(items).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum();
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = CoolWipsEconomy.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
