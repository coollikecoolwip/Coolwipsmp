package io.github.coollikecoolwip.economy;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhysicalSellChestTest {
    private CoolWipsEconomy plugin;
    private final UUID owner = UUID.randomUUID();
    private final NamespacedKey ownerKey = new NamespacedKey("coolwipseconomy", "sell-chest-owner");
    private final World world = mock(World.class);
    private final Map<String, ReentrantLock> chestLocks = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() throws Exception {
        plugin = mock(CoolWipsEconomy.class, CALLS_REAL_METHODS);
        setField("sellChestOwnerKey", ownerKey);
        setField("sellChestLocks", chestLocks);
        when(world.getUID()).thenReturn(UUID.randomUUID());
    }

    @Test
    void unrelatedNearbyChestIsNeverMarkedAsTheOtherHalf() throws Exception {
        Chest single = chest(0, true);
        Inventory singleInventory = mock(Inventory.class);
        when(single.getInventory()).thenReturn(singleInventory);
        when(singleInventory.getHolder()).thenReturn(single);

        assertNull(adjacent(single.getBlock()));
        verify(single.getBlock(), never()).getRelative(any(org.bukkit.block.BlockFace.class));
    }

    @Test
    void usesTheActualConnectedHalfFromEitherSide() throws Exception {
        Chest[] sides = doubleChest(true, true);
        assertSame(sides[1].getBlock(), adjacent(sides[0].getBlock()));
        assertSame(sides[0].getBlock(), adjacent(sides[1].getBlock()));
    }

    @Test
    void processingLeftHalfBlocksOpeningTheRightHalf() {
        Chest[] sides = doubleChest(true, true);
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        chestLocks.put(key(sides[0].getBlock()), lock);
        Player player = player(owner);
        Block right = sides[1].getBlock();
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(right);
        when(event.getPlayer()).thenReturn(player);
        try {
            plugin.onSellChestOpen(event);
            verify(event).setCancelled(true);
        } finally {
            lock.unlock();
        }
    }

    @Test
    void attachingAnUnmarkedHalfDoesNotBypassOwnerProtection() {
        Chest[] sides = doubleChest(true, false);
        Block right = sides[1].getBlock();
        Player stranger = player(UUID.randomUUID());
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(right);
        when(event.getPlayer()).thenReturn(stranger);

        plugin.onSellChestOpen(event);
        verify(event).setCancelled(true);
    }

    @Test
    void cannotBreakEitherHalfDuringAnOutstandingPayout() {
        Chest[] sides = doubleChest(true, true);
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        chestLocks.put(key(sides[0].getBlock()), lock);
        Block right = sides[1].getBlock();
        Player player = player(owner);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(right);
        when(event.getPlayer()).thenReturn(player);
        try {
            plugin.onSellChestBreak(event);
            verify(event).setCancelled(true);
            verify(sides[0].getPersistentDataContainer(), never()).remove(any());
            verify(sides[1].getPersistentDataContainer(), never()).remove(any());
        } finally {
            lock.unlock();
        }
    }

    @Test
    void onlyAcceptedBreakEventsRemoveChestOwnership() throws Exception {
        Method guard = CoolWipsEconomy.class.getMethod("onSellChestBreak", BlockBreakEvent.class);
        Method cleanup = CoolWipsEconomy.class.getMethod("onSellChestBroken", BlockBreakEvent.class);
        assertTrue(guard.getAnnotation(EventHandler.class).ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, guard.getAnnotation(EventHandler.class).priority());
        assertTrue(cleanup.getAnnotation(EventHandler.class).ignoreCancelled());
        assertEquals(EventPriority.MONITOR, cleanup.getAnnotation(EventHandler.class).priority());
    }

    private Chest chest(int x, boolean marked) {
        Chest chest = mock(Chest.class);
        Block block = mock(Block.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(block.getState()).thenReturn(chest);
        when(block.getType()).thenReturn(Material.CHEST);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(chest.getBlock()).thenReturn(block);
        when(chest.getPersistentDataContainer()).thenReturn(data);
        when(data.get(ownerKey, PersistentDataType.STRING)).thenReturn(marked ? owner.toString() : null);
        return chest;
    }

    private Chest[] doubleChest(boolean leftMarked, boolean rightMarked) {
        Chest left = chest(0, leftMarked);
        Chest right = chest(1, rightMarked);
        Inventory inventory = mock(Inventory.class);
        DoubleChest holder = mock(DoubleChest.class);
        when(inventory.getHolder()).thenReturn(holder);
        when(holder.getLeftSide()).thenReturn(left);
        when(holder.getRightSide()).thenReturn(right);
        when(left.getInventory()).thenReturn(inventory);
        when(right.getInventory()).thenReturn(inventory);
        return new Chest[]{left, right};
    }

    private Player player(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }

    private Block adjacent(Block block) throws Exception {
        Method method = CoolWipsEconomy.class.getDeclaredMethod("adjacentChest", Block.class);
        method.setAccessible(true);
        return (Block) method.invoke(plugin, block);
    }

    private String key(Block block) {
        return world.getUID() + ":" + block.getX() + ":0:0";
    }

    private void setField(String name, Object value) throws Exception {
        Field field = CoolWipsEconomy.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
