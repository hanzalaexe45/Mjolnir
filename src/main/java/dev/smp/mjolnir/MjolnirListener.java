package dev.smp.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public final class MjolnirListener implements Listener {

    private final MjolnirPlugin plugin;

    MjolnirListener(MjolnirPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ using the hammer

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!plugin.holdsHammer(p)) return;

        if (!plugin.isWorthy(p)) {
            e.setCancelled(true);
            plugin.reject(p);
            return;
        }
        Block b = e.getClickedBlock();
        // let chests, doors, buttons... work normally unless sneaking
        if (b != null && !p.isSneaking() && b.getType().isInteractable()) return;

        e.setCancelled(true);
        if (p.isSneaking()) {
            plugin.cycle(p);
        } else {
            plugin.abilities.use(p);
        }
    }

    // Did a protection plugin cancel one of our internal hits?
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInternalDamageResult(EntityDamageByEntityEvent e) {
        if (plugin.internalDamage) plugin.internalBlocked = e.isCancelled();
    }

    // Fully charged melee hits call down lightning
    @EventHandler(ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent e) {
        if (plugin.internalDamage) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity v)) return;
        if (!plugin.getConfig().getBoolean("melee-lightning.enabled", true)) return;
        if (!plugin.holdsHammer(p) || !plugin.isWorthy(p)) return;
        if (p.getAttackCooldown() < 0.85f) return;
        if (!plugin.meleeReady(p)) return;

        Location fallback = v.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.abilities.strike(
                v.isValid() ? v.getLocation() : fallback, p, 2.0,
                plugin.getConfig().getDouble("lightning-damage", 11.0)));
    }

    // ---------------------------------------------------- damage rules / protection

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        Entity en = e.getEntity();

        // Undestructible: fire, lava, cactus, explosions, void... nothing hurts the dropped hammer
        if (en instanceof Item it && plugin.isHammer(it.getItemStack())) {
            e.setCancelled(true);
            return;
        }
        if (!(en instanceof Player p)) return;

        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.FALL && plugin.abilities.hasNoFall(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (plugin.abilities.isShielded(p.getUniqueId())
                && c != EntityDamageEvent.DamageCause.VOID
                && c != EntityDamageEvent.DamageCause.KILL
                && c != EntityDamageEvent.DamageCause.SUICIDE) {
            e.setCancelled(true);
            return;
        }
        // Energy absorption: lightning feeds the wielder
        if (c == EntityDamageEvent.DamageCause.LIGHTNING && plugin.holdsHammer(p) && plugin.isWorthy(p)) {
            e.setCancelled(true);
            plugin.abilities.absorb(p);
        }
    }

    // If the hammer ever falls out of the world, a fresh one appears at world spawn
    @EventHandler
    public void onRemove(EntityRemoveEvent e) {
        if (e.getCause() != EntityRemoveEvent.Cause.OUT_OF_WORLD) return;
        if (e.getEntity() instanceof Item it && plugin.isHammer(it.getItemStack())) {
            World w = it.getWorld();
            Location spawn = w.getSpawnLocation().add(0.5, 1, 0.5);
            Bukkit.getScheduler().runTask(plugin, () -> w.dropItem(spawn, plugin.createHammer()));
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        Item it = e.getEntity();
        if (!plugin.isHammer(it.getItemStack())) return;
        it.setInvulnerable(true);
        it.setUnlimitedLifetime(true);
        it.setGlowing(true);
        it.setCanMobPickup(false);
    }

    @EventHandler
    public void onDespawn(ItemDespawnEvent e) {
        if (plugin.isHammer(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    // ------------------------------------------------------------- worthiness

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!plugin.isHammer(e.getItem().getItemStack())) return;
        if (e.getEntity() instanceof Player p && plugin.isWorthy(p)) {
            p.getWorld().strikeLightningEffect(p.getLocation());
            p.sendActionBar(Component.text("Thou art worthy.", NamedTextColor.AQUA));
            return;
        }
        e.setCancelled(true);
        if (e.getEntity() instanceof Player p) plugin.reject(p);
    }

    // ------------------------------------------------- always returns to its owner

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        plugin.abilities.cleanup(p, true);
        boolean removed = e.getDrops().removeIf(plugin::isHammer);
        if (removed) {
            p.getPersistentDataContainer().set(plugin.pendingKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.givePending(p), 5L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.givePending(p), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.abilities.cleanup(e.getPlayer(), false);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking()) plugin.abilities.stopFlight(e.getPlayer());
    }
}
