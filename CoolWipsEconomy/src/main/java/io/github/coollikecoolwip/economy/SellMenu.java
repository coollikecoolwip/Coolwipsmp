package io.github.coollikecoolwip.economy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Owns virtual inventories; all methods run on the server thread. */
final class SellMenu implements Listener {
    private final JavaPlugin plugin;
    private final Consumer<Session> seller;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean shuttingDown;

    SellMenu(JavaPlugin plugin, Consumer<Session> seller) {
        this.plugin = plugin;
        this.seller = seller;
    }

    void open(Player player) {
        Session previous = sessions.get(player.getUniqueId());
        if (previous != null) {
            player.sendMessage("§eYour sell menu is already open.");
            return;
        }
        // Closing the previous view may complete another plugin's inventory event.
        player.closeInventory();
        Session session = new Session(player);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(session.inventory);
        // A protection plugin may cancel InventoryOpenEvent.
        if (player.getOpenInventory().getTopInventory() != session.inventory) {
            sessions.remove(player.getUniqueId(), session);
            session.closed = true;
            return;
        }
        player.sendMessage("§aPut items in this chest to sell them automatically. §7Unsellable items are returned when you close it.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) schedule(session);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session session
                && event.getRawSlots().stream().anyMatch(slot -> slot < session.inventory.getSize())) {
            schedule(session);
        }
    }

    private void schedule(Session session) {
        if (session.closed || session.scheduled || shuttingDown) return;
        session.scheduled = true;
        // Wait until clicks, shift-clicks, number keys and drags have been applied.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            session.scheduled = false;
            if (!session.closed && !shuttingDown) seller.accept(session);
        }, 2L);
    }

    void finished(Session session, boolean continueSelling) {
        session.processing = false;
        if (continueSelling) schedule(session);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) close(session, !shuttingDown);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) close(session, false);
    }

    private void close(Session session, boolean sellRemaining) {
        if (session.closed) return;
        // Reserve the last batch before returning leftovers. In-flight batches have
        // already been removed from the menu and cannot also be returned here.
        if (sellRemaining && !session.processing) seller.accept(session);
        session.closed = true;
        sessions.remove(session.owner.getUniqueId(), session);
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack item : session.inventory.getContents()) {
            if (item != null && !item.getType().isAir()) remaining.add(item.clone());
        }
        session.inventory.clear();
        returnToPlayer(session, remaining);
    }

    void restore(Session session, List<ItemStack> items) {
        if (session.closed) {
            returnToPlayer(session, items);
            return;
        }
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack item : items) leftovers.addAll(session.inventory.addItem(item.clone()).values());
        returnToPlayer(session, leftovers);
    }

    private void returnToPlayer(Session session, List<ItemStack> items) {
        // During PlayerQuitEvent the inventory is still saved after this handler.
        // An async failure after logout drops at the captured return location.
        if (session.owner.isOnline()) {
            for (ItemStack item : items) {
                for (ItemStack leftover : session.owner.getInventory().addItem(item.clone()).values()) {
                    session.owner.getWorld().dropItemNaturally(session.owner.getLocation(), leftover);
                }
            }
        } else {
            for (ItemStack item : items) session.returnLocation.getWorld().dropItemNaturally(session.returnLocation, item.clone());
        }
    }

    void shutdown() {
        shuttingDown = true;
        for (Session session : List.copyOf(sessions.values())) {
            close(session, false);
            session.owner.closeInventory();
        }
    }

    static final class Session implements InventoryHolder {
        final Player owner;
        final Location returnLocation;
        final Inventory inventory;
        boolean closed;
        boolean scheduled;
        boolean processing;

        Session(Player owner) {
            this.owner = owner;
            this.returnLocation = owner.getLocation().clone();
            this.inventory = Bukkit.createInventory(this, 27, "Sell Items — Auto Sell");
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
