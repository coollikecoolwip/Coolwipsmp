package io.github.coollikecoolwip.progression;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SlotType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public final class CoolWipsProgression extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final List<String> GATES = List.of(
            "iron", "copper", "gold", "redstone", "lapis", "emerald", "diamonds",
            "nether", "netherite", "end", "enchanting", "brewing", "elytra", "shulker", "totems", "tridents"
    );

    private final EnumMap<Gate, Boolean> gates = new EnumMap<>(Gate.class);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadGates();
        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("progression")).setExecutor(this);
        Objects.requireNonNull(getCommand("progression")).setTabCompleter(this);

        getLogger().info("CoolWips Progression enabled. Locked gates: " +
                gates.entrySet().stream()
                        .filter(e -> !e.getValue())
                        .map(e -> e.getKey().key)
                        .collect(Collectors.joining(", ")));
    }

    private void loadGates() {
        for (Gate gate : Gate.values()) {
            gates.put(gate, getConfig().getBoolean("gates." + gate.key, false));
        }
    }

    private boolean unlocked(Gate gate) {
        return gates.getOrDefault(gate, false);
    }

    private boolean bypass(Player player) {
        return player.hasPermission("coolwipsprogression.bypass")
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR;
    }

    private void deny(Player player, Gate gate) {
        String msg = getConfig().getString(
                "messages.locked",
                "&cProgression locked: &f{gate}&c. An admin has not unlocked it yet."
        );
        player.sendMessage(color(msg.replace("{gate}", gate.display)));
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private boolean locked(Player player, Gate gate) {
        if (unlocked(gate) || bypass(player)) return false;
        deny(player, gate);
        return true;
    }

    private Gate gateForItem(Material type) {
        if (type == Material.IRON_INGOT || type == Material.IRON_NUGGET || type == Material.IRON_BLOCK || isIronEquipment(type)) return Gate.IRON;
        if (type == Material.COPPER_INGOT || type == Material.RAW_COPPER || type == Material.COPPER_BLOCK || type.name().startsWith("COPPER_")) return Gate.COPPER;
        if (type == Material.GOLD_INGOT || type == Material.GOLD_NUGGET || type == Material.GOLD_BLOCK || isGoldEquipment(type)) return Gate.GOLD;
        if (type == Material.REDSTONE || type == Material.REDSTONE_BLOCK || type == Material.REDSTONE_TORCH) return Gate.REDSTONE;
        if (type == Material.LAPIS_LAZULI || type == Material.LAPIS_BLOCK) return Gate.LAPIS;
        if (type == Material.EMERALD || type == Material.EMERALD_BLOCK) return Gate.EMERALD;
        if (type == Material.DIAMOND || type == Material.DIAMOND_BLOCK || isDiamondEquipment(type)) return Gate.DIAMONDS;
        if (type == Material.NETHERITE_INGOT || type == Material.NETHERITE_SCRAP ||
                type == Material.NETHERITE_BLOCK || isNetheriteEquipment(type)) {
            return Gate.NETHERITE;
        }
        if (type == Material.ELYTRA) return Gate.ELYTRA;
        if (type == Material.SHULKER_SHELL || type.name().endsWith("_SHULKER_BOX")) return Gate.SHULKER;
        if (type == Material.TOTEM_OF_UNDYING) return Gate.TOTEMS;
        if (type == Material.TRIDENT) return Gate.TRIDENTS;
        return null;
    }

    private boolean isIronEquipment(Material m) {
        return switch (m) {
            case IRON_SWORD, IRON_PICKAXE, IRON_AXE, IRON_SHOVEL, IRON_HOE,
                 IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS -> true;
            default -> false;
        };
    }

    private boolean isGoldEquipment(Material m) {
        return switch (m) {
            case GOLDEN_SWORD, GOLDEN_PICKAXE, GOLDEN_AXE, GOLDEN_SHOVEL, GOLDEN_HOE,
                 GOLDEN_HELMET, GOLDEN_CHESTPLATE, GOLDEN_LEGGINGS, GOLDEN_BOOTS -> true;
            default -> false;
        };
    }

    private boolean isDiamondEquipment(Material m) {
        return switch (m) {
            case DIAMOND_SWORD, DIAMOND_PICKAXE, DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_HOE,
                 DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS -> true;
            default -> false;
        };
    }

    private boolean isNetheriteEquipment(Material m) {
        return switch (m) {
            case NETHERITE_SWORD, NETHERITE_PICKAXE, NETHERITE_AXE, NETHERITE_SHOVEL, NETHERITE_HOE,
                 NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material type = event.getBlock().getType();

        if (isIronOre(type) && locked(player, Gate.IRON)) { event.setCancelled(true); return; }
        if (isCopperOre(type) && locked(player, Gate.COPPER)) { event.setCancelled(true); return; }
        if (isGoldOre(type) && locked(player, Gate.GOLD)) { event.setCancelled(true); return; }
        if (isRedstoneOre(type) && locked(player, Gate.REDSTONE)) { event.setCancelled(true); return; }
        if (isLapisOre(type) && locked(player, Gate.LAPIS)) { event.setCancelled(true); return; }
        if (isEmeraldOre(type) && locked(player, Gate.EMERALD)) { event.setCancelled(true); return; }

        if (type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE) {
            if (locked(player, Gate.DIAMONDS)) event.setCancelled(true);
            return;
        }

        if (type == Material.ANCIENT_DEBRIS && locked(player, Gate.NETHERITE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        Gate gate = gateForItem(event.getItem().getItemStack().getType());
        if (gate != null && locked(player, gate)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack result = event.getRecipe().getResult();
        Gate gate = gateForItem(result.getType());

        if (gate != null && locked(player, gate)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSmith(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();
        if (result == null || unlocked(Gate.NETHERITE)) return;

        if (isNetheriteEquipment(result.getType()) || result.getType() == Material.NETHERITE_INGOT) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Gate gate = gateForItem(event.getBlockPlaced().getType());

        if (gate != null && locked(player, gate)) {
            event.setCancelled(true);
        }
    }

    private boolean isIronOre(Material m) { return m == Material.IRON_ORE || m == Material.DEEPSLATE_IRON_ORE; }
    private boolean isCopperOre(Material m) { return m == Material.COPPER_ORE || m == Material.DEEPSLATE_COPPER_ORE; }
    private boolean isGoldOre(Material m) { return m == Material.GOLD_ORE || m == Material.DEEPSLATE_GOLD_ORE; }
    private boolean isRedstoneOre(Material m) { return m == Material.REDSTONE_ORE || m == Material.DEEPSLATE_REDSTONE_ORE; }
    private boolean isLapisOre(Material m) { return m == Material.LAPIS_ORE || m == Material.DEEPSLATE_LAPIS_ORE; }
    private boolean isEmeraldOre(Material m) { return m == Material.EMERALD_ORE || m == Material.DEEPSLATE_EMERALD_ORE; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item != null) {
            Gate gate = gateForItem(item.getType());
            if (gate != null && locked(player, gate)) {
                event.setCancelled(true);
                return;
            }
        }

        if (event.getAction().isRightClick() && event.getClickedBlock() != null) {
            Material clicked = event.getClickedBlock().getType();
            if (clicked == Material.ENCHANTING_TABLE && locked(player, Gate.ENCHANTING)) { event.setCancelled(true); return; }
            if (clicked == Material.BREWING_STAND && locked(player, Gate.BREWING)) { event.setCancelled(true); return; }
        }

        if (event.getAction().isRightClick()
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.END_PORTAL_FRAME
                && locked(player, Gate.END)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Gate gate = gateForItem(event.getItem().getType());
        if (gate != null && locked(event.getPlayer(), gate)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player == null || unlocked(Gate.NETHER) || bypass(player)) return;

        if (event.getCause() == BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                || event.getCause() == BlockIgniteEvent.IgniteCause.FIREBALL) {
            event.setCancelled(true);
            deny(player, Gate.NETHER);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;

        Player player = event.getPlayer();
        World.Environment from = event.getFrom().getWorld().getEnvironment();
        World.Environment to = event.getTo().getWorld().getEnvironment();

        if (to == World.Environment.NETHER && from != World.Environment.NETHER
                && locked(player, Gate.NETHER)) {
            event.setCancelled(true);
            return;
        }

        if (to == World.Environment.THE_END && from != World.Environment.THE_END
                && locked(player, Gate.END)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();

        if (world.getEnvironment() == World.Environment.NETHER
                && !unlocked(Gate.NETHER) && !bypass(player)) {
            player.teleport(findSafeOverworld(player.getLocation()));
            deny(player, Gate.NETHER);
        } else if (world.getEnvironment() == World.Environment.THE_END
                && !unlocked(Gate.END) && !bypass(player)) {
            player.teleport(findSafeOverworld(player.getLocation()));
            deny(player, Gate.END);
        }
    }

    private Location findSafeOverworld(Location source) {
        World overworld = getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .findFirst()
                .orElse(getServer().getWorlds().get(0));

        Location result = source.clone();
        result.setWorld(overworld);
        return result;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEquip(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack candidate = event.getCursor();
        if (candidate == null || candidate.getType() == Material.AIR) {
            candidate = event.getCurrentItem();
        }
        if (candidate == null || candidate.getType() == Material.AIR) return;

        Gate gate = gateForItem(candidate.getType());
        if (gate == null || event.getSlotType() != SlotType.ARMOR) return;

        if (locked(player, gate)) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("coolwipsprogression.admin")) {
            sender.sendMessage(color("&cYou do not have permission."));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(color("&6&lCoolWips Progression"));
            for (Gate gate : Gate.values()) {
                String state = unlocked(gate) ? "&aUNLOCKED" : "&cLOCKED";
                sender.sendMessage(color(state + " &f" + gate.display
                        + " &7(/progression unlock " + gate.key + ")"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadGates();
            sender.sendMessage(color("&aProgression configuration reloaded."));
            return true;
        }

        if (args[0].equalsIgnoreCase("unlock") || args[0].equalsIgnoreCase("lock")) {
            if (args.length < 2) {
                sender.sendMessage(color("&cUsage: /progression " + args[0] + " <gate>"));
                return true;
            }

            Gate gate = parseGate(args[1]);
            if (gate == null) {
                sender.sendMessage(color("&cUnknown gate. Available: " + String.join(", ", GATES)));
                return true;
            }

            boolean value = args[0].equalsIgnoreCase("unlock");
            gates.put(gate, value);
            getConfig().set("gates." + gate.key, value);
            saveConfig();

            sender.sendMessage(color(value
                    ? "&aUnlocked &f" + gate.display
                    : "&cLocked &f" + gate.display));
            return true;
        }

        sender.sendMessage(color("&cUsage: /progression <status|unlock|lock|reload> [gate]"));
        return true;
    }

    private Gate parseGate(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (Gate gate : Gate.values()) {
            if (gate.key.equals(lower) || gate.aliases.contains(lower)) return gate;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], List.of("status", "unlock", "lock", "reload"));
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("unlock") || args[0].equalsIgnoreCase("lock"))) {
            return partial(args[1], GATES);
        }

        return List.of();
    }

    private List<String> partial(String input, Collection<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.startsWith(lower)).toList();
    }

    private enum Gate {
        DIAMONDS("diamonds", "Diamonds", "diamond"),
        NETHER("nether", "The Nether"),
        NETHERITE("netherite", "Netherite"),
        END("end", "The End"),
        ELYTRA("elytra", "Elytra"),
        SHULKER("shulker", "Shulker"),
        TOTEMS("totems", "Totems", "totem"),
        TRIDENTS("tridents", "Tridents", "trident"),
        IRON("iron", "Iron"),
        COPPER("copper", "Copper"),
        GOLD("gold", "Gold"),
        REDSTONE("redstone", "Redstone"),
        LAPIS("lapis", "Lapis Lazuli", "lapis_lazuli"),
        EMERALD("emerald", "Emerald"),
        ENCHANTING("enchanting", "Enchanting"),
        BREWING("brewing", "Brewing");

        final String key;
        final String display;
        final Set<String> aliases;

        Gate(String key, String display, String... aliases) {
            this.key = key;
            this.display = display;
            this.aliases = Set.of(aliases);
        }
    }
}
