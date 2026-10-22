package io.github.coollikecoolwip.economy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SellMenuTest {
    MockedStatic<Bukkit> bukkit;
    JavaPlugin plugin;
    Player player;
    PlayerInventory playerInventory;
    Inventory inventory;
    BukkitScheduler scheduler;
    SellMenu menu;
    SellMenu.Session session;
    AtomicReference<ItemStack[]> contents;
    List<Runnable> tasks;
    List<SellMenu.Session> sales;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        plugin = mock(JavaPlugin.class);
        player = mock(Player.class);
        playerInventory = mock(PlayerInventory.class);
        inventory = mock(Inventory.class);
        scheduler = mock(BukkitScheduler.class);
        contents = new AtomicReference<>(new ItemStack[27]);
        tasks = new ArrayList<>();
        sales = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.getLocation()).thenReturn(new Location(mock(World.class), 0, 64, 0));
        when(playerInventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenAnswer(call -> contents.get());
        when(inventory.getSize()).thenReturn(27);
        doAnswer(call -> { contents.set(new ItemStack[27]); return null; }).when(inventory).clear();
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), anyString()))
                .thenAnswer(call -> { session = call.getArgument(0); return inventory; });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(2L)))
                .thenAnswer(call -> { tasks.add(call.getArgument(1)); return null; });
        menu = new SellMenu(plugin, sales::add);
        menu.open(player);
        when(inventory.getHolder()).thenReturn(session);
    }

    @AfterEach void cleanup() { bukkit.close(); }

    ItemStack item() {
        ItemStack item = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(item.getType()).thenReturn(material);
        when(item.clone()).thenReturn(item);
        return item;
    }

    void close() {
        InventoryCloseEvent event = mock(InventoryCloseEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        menu.onClose(event);
    }

    @Test void clickAndDragBatchAfterInventoryChanges() {
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getInventory()).thenReturn(inventory);
        menu.onClick(click);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getInventory()).thenReturn(inventory);
        when(drag.getRawSlots()).thenReturn(Set.of(0, 1));
        menu.onDrag(drag);
        assertTrue(sales.isEmpty(), "Must wait for Bukkit to apply the movement");
        assertEquals(1, tasks.size());
        tasks.removeFirst().run();
        assertEquals(List.of(session), sales);
    }

    @Test void quickCloseSellsFinalBatchAndReturnsUnsoldItemsOnce() {
        ItemStack item = item();
        contents.get()[0] = item;
        close();
        close();
        assertEquals(List.of(session), sales);
        verify(playerInventory).addItem(item);
        assertTrue(session.closed);
        assertNull(contents.get()[0]);
    }

    @Test void closeWhileProcessingDoesNotSellOrReturnEscrowTwice() {
        session.processing = true;
        close();
        assertTrue(sales.isEmpty());
        ItemStack escrow = item();
        menu.restore(session, List.of(escrow));
        verify(playerInventory).addItem(escrow);
        menu.finished(session, true);
        assertTrue(tasks.isEmpty());
    }

    @Test void failureWhileOpenRestoresIntoMenu() {
        ItemStack escrow = item();
        when(inventory.addItem(escrow)).thenReturn(new HashMap<>());
        menu.restore(session, List.of(escrow));
        verify(inventory).addItem(escrow);
        verify(playerInventory, never()).addItem(any(ItemStack.class));
    }

    @Test void shutdownReturnsContentsWithoutStartingAnotherPayment() {
        ItemStack item = item();
        contents.get()[0] = item;
        menu.shutdown();
        assertTrue(sales.isEmpty());
        verify(playerInventory).addItem(item);
        assertTrue(session.closed);
    }

    @Test void scheduledClickCannotSellAgainAfterClose() {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        menu.onClick(event);
        close();
        tasks.removeFirst().run();
        assertEquals(1, sales.size());
    }

    @Test void quitReturnsUnsubmittedItemsWithoutStartingPayment() {
        ItemStack item = item();
        contents.get()[0] = item;
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        menu.onQuit(event);
        assertTrue(sales.isEmpty());
        verify(playerInventory).addItem(item);
        assertTrue(session.closed);
    }
}
