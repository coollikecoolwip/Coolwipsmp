package io.github.coollikecoolwip.progression;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoolWipsProgressionTest {
    private CoolWipsProgression plugin;
    private Player player;
    private Server server;
    private World overworld;
    private Location spawn;
    private Map<Enum, Boolean> gates;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() throws ReflectiveOperationException {
        // Exercise the real event handlers without starting a Minecraft server.
        plugin = mock(CoolWipsProgression.class, CALLS_REAL_METHODS);
        server = mock(Server.class);
        player = mock(Player.class);
        overworld = world(World.Environment.NORMAL);
        spawn = new Location(overworld, 12.5, 75, -3.5);
        when(overworld.getSpawnLocation()).thenReturn(spawn);
        when(server.getWorlds()).thenReturn(List.of(overworld));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        doReturn(server).when(plugin).getServer();
        doReturn(new YamlConfiguration()).when(plugin).getConfig();

        Field field = CoolWipsProgression.class.getDeclaredField("gates");
        field.setAccessible(true);
        Class gateType = Class.forName(CoolWipsProgression.class.getName() + "$Gate");
        gates = new EnumMap(gateType);
        field.set(plugin, gates);
    }

    @ParameterizedTest
    @EnumSource(value = World.Environment.class, names = {"NETHER", "THE_END"})
    void respawnInUnlockedDimensionStaysAtRespawnBlock(World.Environment environment) {
        unlock(environment == World.Environment.NETHER ? "NETHER" : "END");
        PlayerRespawnEvent event = respawnEvent(environment);

        plugin.onRespawn(event);

        verify(event, never()).setRespawnLocation(any());
    }

    @ParameterizedTest
    @EnumSource(value = World.Environment.class, names = {"NETHER", "THE_END"})
    void respawnInLockedDimensionUsesOverworldSpawn(World.Environment environment) {
        PlayerRespawnEvent event = respawnEvent(environment);

        plugin.onRespawn(event);

        verify(event).setRespawnLocation(spawn);
    }

    @Test
    void bypassPreservesLockedDimensionRespawn() {
        when(player.hasPermission("coolwipsprogression.bypass")).thenReturn(true);
        PlayerRespawnEvent event = respawnEvent(World.Environment.NETHER);

        plugin.onRespawn(event);

        verify(event, never()).setRespawnLocation(any());
    }

    @Test
    void ejectionUsesSpawnInsteadOfCoordinatesFromAnotherDimension() {
        World nether = world(World.Environment.NETHER);
        when(player.getWorld()).thenReturn(nether);
        when(player.teleport(spawn)).thenReturn(true);
        PlayerChangedWorldEvent event = mock(PlayerChangedWorldEvent.class);
        when(event.getPlayer()).thenReturn(player);

        plugin.onChangedWorld(event);

        verify(player).teleport(spawn);
    }

    @Test
    void missingOverworldDoesNotTeleportBackIntoLockedWorld() {
        World nether = world(World.Environment.NETHER);
        when(server.getWorlds()).thenReturn(List.of(nether));
        when(player.getWorld()).thenReturn(nether);
        PlayerChangedWorldEvent event = mock(PlayerChangedWorldEvent.class);
        when(event.getPlayer()).thenReturn(player);

        plugin.onChangedWorld(event);

        verify(player, never()).teleport(any(Location.class));
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"SHULKER_BOX", "WHITE_SHULKER_BOX", "BLACK_SHULKER_BOX"})
    void everyShulkerBoxVariantCannotBeBrokenPlacedOrDispensedWhileLocked(Material material) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        when(breaking.getPlayer()).thenReturn(player);
        when(breaking.getBlock()).thenReturn(block);
        BlockPlaceEvent placing = mock(BlockPlaceEvent.class);
        when(placing.getPlayer()).thenReturn(player);
        when(placing.getBlockPlaced()).thenReturn(block);
        BlockDispenseEvent dispensing = mock(BlockDispenseEvent.class);
        ItemStack box = item(material);
        when(dispensing.getItem()).thenReturn(box);

        plugin.onBreak(breaking);
        plugin.onPlace(placing);
        plugin.onDispenseShulker(dispensing);

        verify(breaking).setCancelled(true);
        verify(placing).setCancelled(true);
        verify(dispensing).setCancelled(true);
    }

    @Test
    void uncoloredShulkerBoxCannotBePickedUpOrCraftedWhileLocked() {
        ItemStack box = item(Material.SHULKER_BOX);
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(box);
        PlayerPickupItemEvent pickup = mock(PlayerPickupItemEvent.class);
        when(pickup.getPlayer()).thenReturn(player);
        when(pickup.getItem()).thenReturn(entity);
        Recipe recipe = mock(Recipe.class);
        when(recipe.getResult()).thenReturn(box);
        CraftItemEvent craft = mock(CraftItemEvent.class);
        when(craft.getWhoClicked()).thenReturn(player);
        when(craft.getRecipe()).thenReturn(recipe);

        plugin.onPickup(pickup);
        plugin.onCraft(craft);

        verify(pickup).setCancelled(true);
        verify(craft).setCancelled(true);
    }

    @Test
    void unlockedShulkerBoxCanBePlaced() {
        unlock("SHULKER");
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.SHULKER_BOX);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlockPlaced()).thenReturn(block);

        plugin.onPlace(event);

        verify(event, never()).setCancelled(true);
    }

    @ParameterizedTest
    @EnumSource(value = EquipmentSlot.class, names = {"HAND", "OFF_HAND"})
    void lockedTotemsCannotResurrectFromEitherHand(EquipmentSlot hand) {
        EntityResurrectEvent event = resurrection(hand);

        plugin.onTotemResurrect(event);

        verify(event).setCancelled(true);
    }

    @Test
    void unlockedTotemsCanResurrect() {
        unlock("TOTEMS");
        EntityResurrectEvent event = resurrection(EquipmentSlot.OFF_HAND);

        plugin.onTotemResurrect(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void bypassCanUseTotems() {
        when(player.hasPermission("coolwipsprogression.bypass")).thenReturn(true);
        EntityResurrectEvent event = resurrection(EquipmentSlot.HAND);

        plugin.onTotemResurrect(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void resurrectionWithoutATotemIsUnaffected() {
        EntityResurrectEvent event = resurrection(null);

        plugin.onTotemResurrect(event);

        verify(event, never()).setCancelled(true);
    }

    private void unlock(String gate) {
        // Read the enum constants from the production field's declared key type.
        try {
            Class<?> gateType = Class.forName(CoolWipsProgression.class.getName() + "$Gate");
            for (Object value : gateType.getEnumConstants()) {
                if (((Enum<?>) value).name().equals(gate)) {
                    gates.put((Enum) value, true);
                    return;
                }
            }
            throw new IllegalArgumentException("Unknown gate: " + gate);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError(exception);
        }
    }

    private PlayerRespawnEvent respawnEvent(World.Environment environment) {
        PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
        World dimension = world(environment);
        when(event.getPlayer()).thenReturn(player);
        when(event.getRespawnLocation()).thenReturn(new Location(dimension, 500, -60, 900));
        return event;
    }

    private EntityResurrectEvent resurrection(EquipmentSlot hand) {
        EntityResurrectEvent event = mock(EntityResurrectEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getHand()).thenReturn(hand);
        return event;
    }

    private World world(World.Environment environment) {
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(environment);
        return world;
    }

    private ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        return item;
    }
}
