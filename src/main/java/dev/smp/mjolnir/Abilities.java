package dev.smp.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class Abilities {

    private final MjolnirPlugin plugin;
    private final Map<UUID, ThrowTask> throwing = new HashMap<>();
    private final Map<UUID, BukkitTask> flights = new HashMap<>();
    private final Map<UUID, Boolean> flightPrevAllow = new HashMap<>();
    private final Set<UUID> shielded = new HashSet<>();
    private final Set<UUID> charging = new HashSet<>();
    private final Map<UUID, Long> noFallUntil = new HashMap<>();
    private final Random rng = new Random();

    Abilities(MjolnirPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ helpers

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("abilities." + path, def);
    }

    private double lightningDamage() {
        return plugin.getConfig().getDouble("lightning-damage", 11.0);
    }

    public boolean isThrowing(UUID id) {
        return throwing.containsKey(id);
    }

    public boolean isShielded(UUID id) {
        return shielded.contains(id);
    }

    public boolean hasNoFall(UUID id) {
        Long until = noFallUntil.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    private Location ground(Location l) {
        RayTraceResult r = l.getWorld().rayTraceBlocks(l, new Vector(0, -1, 0), 64,
                FluidCollisionMode.NEVER, true);
        return r == null ? l : r.getHitPosition().toLocation(l.getWorld());
    }

    /** Wielders of a worthy hammer soak up lightning and energy. */
    public void absorb(Player p) {
        double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        p.setHealth(Math.min(max, p.getHealth() + 4.0));
        p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.2);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.8f);
    }

    /**
     * True damage: ignores armor, Protection and Resistance. Absorption hearts are eaten first.
     * Respects claim/PvP plugins by first sending a tiny normal hit and checking it was not cancelled.
     */
    public boolean smite(Player owner, LivingEntity v, double dmg) {
        if (v.equals(owner) || !v.isValid() || v.isDead() || v instanceof ArmorStand) return false;
        if (v instanceof Player vp) {
            if (vp.getGameMode() == GameMode.CREATIVE || vp.getGameMode() == GameMode.SPECTATOR) return false;
            if (plugin.holdsHammer(vp) && plugin.isWorthy(vp)) {
                absorb(vp);
                return false;
            }
            if (shielded.contains(vp.getUniqueId())) return false;
        }
        if (v.isInvulnerable()) return false;

        v.setNoDamageTicks(0);
        plugin.internalBlocked = false;
        plugin.internalDamage = true;
        try {
            v.damage(0.1, owner);
        } finally {
            plugin.internalDamage = false;
        }
        if (plugin.internalBlocked) {
            plugin.internalBlocked = false;
            return false;
        }
        if (v.isDead() || !v.isValid()) return true;

        double rem = dmg;
        double abs = v.getAbsorptionAmount();
        if (abs > 0) {
            double take = Math.min(abs, rem);
            v.setAbsorptionAmount(abs - take);
            rem -= take;
        }
        if (rem > 0) {
            double h = v.getHealth() - rem;
            v.setHealth(Math.max(0.0, h));
        }
        v.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, v.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.2);
        return true;
    }

    public void strike(Location loc, Player owner, double radius, double dmg) {
        World w = loc.getWorld();
        w.strikeLightningEffect(loc);
        w.spawnParticle(Particle.ELECTRIC_SPARK, loc, 80, radius / 2, 1.0, radius / 2, 0.3);
        w.spawnParticle(Particle.EXPLOSION, loc, 2, 0.4, 0.2, 0.4, 0);
        w.playSound(loc, Sound.ITEM_TRIDENT_THUNDER, 2f, 1f);
        for (LivingEntity e : w.getNearbyLivingEntities(loc, radius)) {
            smite(owner, e, dmg);
        }
    }

    // --------------------------------------------------------------- dispatcher

    public void use(Player p) {
        UUID id = p.getUniqueId();
        if (throwing.containsKey(id) || charging.contains(id)) return;
        Ability a = plugin.selected(p);
        long rem = plugin.remainingMs(p, a);
        if (rem > 0) {
            p.sendActionBar(Component.text(a.display + " recharging: "
                    + String.format("%.1fs", rem / 1000.0), NamedTextColor.RED));
            return;
        }
        boolean ok = switch (a) {
            case THROW -> doThrow(p);
            case LIGHTNING -> thunderStrike(p);
            case STORM -> storm(p);
            case FLIGHT -> flight(p);
            case GOD_BLAST -> godBlast(p);
            case FORCE_FIELD -> forceField(p);
            case BIFROST -> bifrost(p);
            case DETECT -> detect(p);
        };
        if (ok) plugin.startCooldown(p, a);
    }

    // ------------------------------------------------------------ Thunder Strike

    private boolean thunderStrike(Player p) {
        double range = cfg("lightning.range", 64);
        double radius = cfg("lightning.radius", 3.0);
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.6,
                en -> !en.equals(p) && en instanceof LivingEntity && !(en instanceof ArmorStand));
        Location target;
        if (r != null && r.getHitEntity() != null) {
            target = r.getHitEntity().getLocation();
        } else if (r != null) {
            target = r.getHitPosition().toLocation(p.getWorld());
        } else {
            target = ground(eye.clone().add(eye.getDirection().multiply(range)));
        }
        strike(target, p, radius, lightningDamage());
        return true;
    }

    // ------------------------------------------------------------------ Storm

    private boolean storm(Player p) {
        World w = p.getWorld();
        int weatherTicks = (int) (cfg("storm.weather-seconds", 300) * 20);
        int strikeTicks = (int) (cfg("storm.strike-seconds", 15) * 20);
        double radius = cfg("storm.radius", 24.0);
        boolean hitPlayers = plugin.getConfig().getBoolean("abilities.storm.hits-players", true);

        w.setStorm(true);
        w.setThundering(true);
        w.setWeatherDuration(weatherTicks);
        w.setThunderDuration(weatherTicks);
        w.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.8f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= strikeTicks) {
                    cancel();
                    return;
                }
                t += 2;
                if (t % 8 != 0) return;

                List<LivingEntity> targets = new ArrayList<>();
                for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
                    if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) continue;
                    if (e instanceof Tameable tm && p.equals(tm.getOwner())) continue;
                    if (e instanceof Player) {
                        if (hitPlayers) targets.add(le);
                    } else if (e instanceof Enemy) {
                        targets.add(le);
                    }
                }
                if (!targets.isEmpty()) {
                    LivingEntity pick = targets.get(rng.nextInt(targets.size()));
                    strike(pick.getLocation(), p, 2.0, lightningDamage());
                } else {
                    Location l = p.getLocation().add((rng.nextDouble() - 0.5) * radius * 1.6, 0,
                            (rng.nextDouble() - 0.5) * radius * 1.6);
                    l.getWorld().strikeLightningEffect(ground(l.clone().add(0, 20, 0)));
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return true;
    }

    // ----------------------------------------------------------------- Flight

    private boolean flight(Player p) {
        UUID id = p.getUniqueId();
        if (flights.containsKey(id)) return false;
        int ticks = (int) (cfg("flight.duration-seconds", 5.0) * 20);
        double speed = cfg("flight.speed", 1.6);

        flightPrevAllow.put(id, p.getAllowFlight());
        p.setAllowFlight(true); // stops the vanilla "floating too long" kick during the flight
        noFallUntil.put(id, Long.MAX_VALUE);
        p.setVelocity(new Vector(0, 0.7, 0));
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 1.5f, 1.2f);

        BukkitTask task = new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || elapsed++ >= ticks) {
                    stopFlight(p);
                    return;
                }
                p.setVelocity(p.getEyeLocation().getDirection().multiply(speed));
                p.setFallDistance(0f);
                Location l = p.getLocation();
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, l, 6, 0.3, 0.3, 0.3, 0.05);
                p.getWorld().spawnParticle(Particle.CLOUD, l, 2, 0.2, 0.2, 0.2, 0.01);
                if (elapsed % 10 == 0) {
                    p.getWorld().playSound(l, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.4f, 1.8f);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        flights.put(id, task);
        return true;
    }

    public void stopFlight(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask task = flights.remove(id);
        if (task == null) return;
        task.cancel();
        Boolean prev = flightPrevAllow.remove(id);
        if (p.isOnline() && prev != null && !prev
                && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        noFallUntil.put(id, System.currentTimeMillis() + 6000L);
    }

    // --------------------------------------------------------------- God Blast

    private boolean godBlast(Player p) {
        double cost = cfg("god-blast.health-cost", 6.0);
        boolean survival = p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
        if (survival && p.getHealth() <= cost + 1.0) {
            p.sendActionBar(Component.text("Not enough life force for the God Blast!", NamedTextColor.RED));
            return false;
        }
        UUID id = p.getUniqueId();
        charging.add(id);
        int chargeTicks = 40;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, chargeTicks, 5, false, false, false));

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    charging.remove(id);
                    cancel();
                    return;
                }
                Location c = p.getLocation().add(0, 1, 0);
                double prog = t / (double) chargeTicks;
                double r = 3.0 * (1 - prog) + 0.5;
                for (int i = 0; i < 12; i++) {
                    double ang = t * 0.5 + i * Math.PI / 6;
                    c.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                            c.clone().add(Math.cos(ang) * r, prog * 2 - 1, Math.sin(ang) * r), 1, 0, 0, 0, 0);
                }
                if (t % 8 == 0) {
                    c.getWorld().playSound(c, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 0.5f + (float) prog);
                }
                if (++t >= chargeTicks) {
                    cancel();
                    charging.remove(id);
                    releaseGodBlast(p);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private void releaseGodBlast(Player p) {
        Location c = p.getLocation();
        World w = c.getWorld();
        double radius = cfg("god-blast.radius", 12.0);
        double dmg = cfg("god-blast.damage", 30.0);
        double cost = cfg("god-blast.health-cost", 6.0);

        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            p.setHealth(Math.max(1.0, p.getHealth() - cost));
        }

        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f);
        w.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 3f, 0.7f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 2, 0.5, 0.5, 0.5, 0);
        w.spawnParticle(Particle.ELECTRIC_SPARK, c.clone().add(0, 1, 0), 400, 3, 2, 3, 0.6);

        // expanding shockwave ring
        new BukkitRunnable() {
            int t = 1;

            @Override
            public void run() {
                double r = radius * t / 10.0;
                for (int i = 0; i < 48; i++) {
                    double a = i * Math.PI * 2 / 48;
                    Location l = c.clone().add(Math.cos(a) * r, 0.3, Math.sin(a) * r);
                    w.spawnParticle(Particle.ELECTRIC_SPARK, l, 2, 0.1, 0.2, 0.1, 0.05);
                    w.spawnParticle(Particle.END_ROD, l, 1, 0, 0.1, 0, 0.02);
                }
                if (++t > 10) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);

        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            w.strikeLightningEffect(ground(c.clone().add(Math.cos(a) * radius * 0.7, 10, Math.sin(a) * radius * 0.7)));
        }

        for (LivingEntity e : w.getNearbyLivingEntities(c, radius)) {
            if (e.equals(p)) continue;
            if (smite(p, e, dmg)) {
                Vector kb = e.getLocation().toVector().subtract(c.toVector());
                kb.setY(0);
                if (kb.lengthSquared() < 0.01) kb = new Vector(0, 0, 1);
                e.setVelocity(kb.normalize().multiply(2.2).setY(0.9));
            }
        }

        if (plugin.getConfig().getBoolean("abilities.god-blast.break-blocks", false)) {
            w.createExplosion(c, 6f, false, true, p);
        }
    }

    // ------------------------------------------------------------- Force Field

    private boolean forceField(Player p) {
        UUID id = p.getUniqueId();
        if (shielded.contains(id)) return false;
        shielded.add(id);
        int dur = (int) (cfg("force-field.duration-seconds", 8.0) * 20);
        double radius = cfg("force-field.radius", 3.5);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.5f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= dur) {
                    shielded.remove(id);
                    if (p.isOnline()) {
                        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.5f);
                    }
                    cancel();
                    return;
                }
                World w = p.getWorld();
                Location c = p.getLocation().add(0, 1, 0);
                for (int i = 0; i < 14; i++) {
                    double a = t * 0.4 + i * (2 * Math.PI / 14);
                    double x = Math.cos(a) * radius;
                    double z = Math.sin(a) * radius;
                    w.spawnParticle(Particle.ELECTRIC_SPARK, c.clone().add(x, Math.sin(a * 2) * 0.9, z), 1, 0, 0, 0, 0);
                    w.spawnParticle(Particle.END_ROD, c.clone().add(x, Math.sin(a * 2 + Math.PI) * 0.9, z), 1, 0, 0, 0, 0);
                }
                if (t % 20 == 0) w.playSound(c, Sound.BLOCK_BEACON_AMBIENT, 0.6f, 1.5f);

                for (Entity e : w.getNearbyEntities(c, radius, radius, radius)) {
                    if (e.equals(p) || e instanceof Item || e instanceof ExperienceOrb) continue;
                    Vector away = e.getLocation().toVector().subtract(p.getLocation().toVector());
                    away.setY(0);
                    if (away.lengthSquared() < 1.0E-4) away = new Vector(0, 0, 1);
                    away.normalize();
                    if (e instanceof Projectile pr) {
                        pr.setVelocity(away.multiply(1.2).setY(0.1));
                    } else if (e instanceof LivingEntity) {
                        e.setVelocity(away.multiply(0.7).setY(0.25));
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ------------------------------------------------------------------ Bifröst

    private static final Color[] RAINBOW = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME,
            Color.AQUA, Color.BLUE, Color.PURPLE};

    private boolean bifrost(Player p) {
        double range = cfg("bifrost.range", 120.0);
        Location eye = p.getEyeLocation();
        World w = p.getWorld();
        RayTraceResult r = w.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        Location dest;
        if (r != null) {
            BlockFace f = r.getHitBlockFace();
            Vector n = f == null ? new Vector() : f.getDirection();
            dest = r.getHitPosition().clone().add(n.multiply(0.6)).toLocation(w);
        } else {
            dest = eye.clone().add(eye.getDirection().multiply(range));
        }
        dest.setYaw(eye.getYaw());
        dest.setPitch(eye.getPitch());

        int tries = 0;
        while (tries++ < 8 && (!dest.getBlock().isPassable()
                || !dest.clone().add(0, 1, 0).getBlock().isPassable())) {
            dest.add(0, 1, 0);
        }
        if (dest.getY() >= w.getMaxHeight() - 2 || !dest.getBlock().isPassable()) {
            p.sendActionBar(Component.text("No safe place to open the Bifröst there.", NamedTextColor.RED));
            return false;
        }

        UUID id = p.getUniqueId();
        charging.add(id);
        Location from = p.getLocation();
        Location target = dest;

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    charging.remove(id);
                    cancel();
                    return;
                }
                Location feet = p.getLocation();
                for (int i = 0; i < 20; i++) {
                    double a = t * 0.6 + i * Math.PI / 10;
                    Color col = RAINBOW[i % RAINBOW.length];
                    feet.getWorld().spawnParticle(Particle.DUST, feet.clone().add(Math.cos(a) * 1.2,
                            t / 20.0 * 2.0, Math.sin(a) * 1.2), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.6f));
                }
                if (t % 5 == 0) feet.getWorld().playSound(feet, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 0.6f + t / 40f);
                if (++t < 20) return;

                cancel();
                charging.remove(id);

                // rainbow beam between start and destination
                Vector dir = target.toVector().subtract(from.toVector().add(new Vector(0, 1, 0)));
                double len = dir.length();
                if (len > 0.1) {
                    dir.normalize();
                    for (double d = 0; d < len; d += 0.8) {
                        Location l = from.clone().add(0, 1, 0).add(dir.clone().multiply(d));
                        Color col = RAINBOW[(int) (d / 0.8) % RAINBOW.length];
                        l.getWorld().spawnParticle(Particle.DUST, l, 2, 0.1, 0.1, 0.1, 0,
                                new Particle.DustOptions(col, 2.0f));
                    }
                }
                from.getWorld().strikeLightningEffect(from);
                p.teleport(target);
                p.setFallDistance(0f);
                noFallUntil.put(id, System.currentTimeMillis() + 6000L);
                target.getWorld().strikeLightningEffect(target);
                target.getWorld().playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.6f);
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // --------------------------------------------------------- Energy Detection

    private boolean detect(Player p) {
        double radius = cfg("detect.radius", 64.0);
        int glowSeconds = (int) cfg("detect.glow-seconds", 8);
        List<LivingEntity> toClear = new ArrayList<>();
        List<String> players = new ArrayList<>();
        int count = 0;

        for (LivingEntity e : p.getWorld().getNearbyLivingEntities(p.getLocation(), radius)) {
            if (e.equals(p) || e instanceof ArmorStand) continue;
            count++;
            if (!e.isGlowing()) {
                e.setGlowing(true);
                toClear.add(e);
            }
            if (e instanceof Player other) {
                players.add(other.getName() + " (" + (int) other.getLocation().distance(p.getLocation()) + "m)");
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (LivingEntity e : toClear) {
                if (e.isValid()) e.setGlowing(false);
            }
        }, glowSeconds * 20L);

        Location c = p.getLocation().add(0, 1, 0);
        p.getWorld().playSound(c, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.8f);
        p.getWorld().spawnParticle(Particle.END_ROD, c, 60, 1.5, 1, 1.5, 0.15);
        p.sendMessage(Component.text("⚡ Mjölnir senses " + count + " beings within " + (int) radius + " blocks.",
                NamedTextColor.AQUA));
        if (!players.isEmpty()) {
            p.sendMessage(Component.text("Players: " + String.join(", ", players), NamedTextColor.YELLOW));
        }
        return true;
    }

    // ------------------------------------------------------------------- Throw

    private boolean doThrow(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!plugin.isHammer(held)) return false;
        ItemStack stack = held.clone();
        int slot = p.getInventory().getHeldItemSlot();
        p.getInventory().setItemInMainHand(null);

        ThrowTask task = new ThrowTask(p, stack, slot);
        throwing.put(p.getUniqueId(), task);
        task.runTaskTimer(plugin, 0L, 1L);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_THROW, 1.5f, 0.6f);
        return true;
    }

    private final class ThrowTask extends BukkitRunnable {
        private final Player p;
        private final ItemStack stack;
        private final int slot;
        private final ItemDisplay disp;
        private final double speed = cfg("throw.speed", 1.7);
        private final double range = cfg("throw.range", 35.0);
        private final Set<UUID> hitOut = new HashSet<>();
        private final Set<UUID> hitBack = new HashSet<>();
        private Location pos;
        private Vector dir;
        private double travelled = 0;
        private boolean returning = false;
        private boolean done = false;
        private float angle = 0f;
        private int life = 0;

        ThrowTask(Player p, ItemStack stack, int slot) {
            this.p = p;
            this.stack = stack;
            this.slot = slot;
            this.dir = p.getEyeLocation().getDirection().normalize();
            this.pos = p.getEyeLocation().add(dir.clone().multiply(0.8)).subtract(0, 0.3, 0);
            this.pos.setDirection(dir);
            this.disp = p.getWorld().spawn(pos, ItemDisplay.class, d -> {
                d.setItemStack(stack);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setPersistent(false);
                d.setTeleportDuration(1);
                d.setInterpolationDuration(1);
                d.setBrightness(new Display.Brightness(15, 15));
            });
        }

        @Override
        public void run() {
            try {
                tick();
            } catch (Exception ex) {
                plugin.getLogger().warning("Mjölnir throw failed, returning hammer: " + ex);
                end(false);
            }
        }

        private void tick() {
            if (!p.isOnline() || p.isDead()) {
                end(p.isDead());
                return;
            }
            World w = pos.getWorld();
            if (!p.getWorld().equals(w) || ++life > 200) {
                end(false);
                return;
            }

            double step = returning ? speed * 1.25 : speed;
            if (!returning) {
                RayTraceResult rr = w.rayTraceBlocks(pos, dir, step, FluidCollisionMode.NEVER, true);
                if (rr != null) {
                    pos = rr.getHitPosition().toLocation(w);
                    strike(pos, p, 3.0, lightningDamage());
                    returning = true;
                } else {
                    pos.add(dir.clone().multiply(step));
                    travelled += step;
                    if (travelled >= range) returning = true;
                }
            } else {
                // Always flies back to the owner's hand and passes straight through obstacles.
                Location target = p.getEyeLocation().subtract(0, 0.5, 0);
                Vector to = target.toVector().subtract(pos.toVector());
                if (to.length() <= step + 0.8) {
                    end(false);
                    return;
                }
                dir = to.normalize();
                pos.add(dir.clone().multiply(step));
            }
            pos.setDirection(dir);
            disp.teleport(pos);

            angle += 0.9f;
            disp.setTransformation(new Transformation(new Vector3f(0, 0, 0),
                    new AxisAngle4f(angle, 1f, 0f, 0f), new Vector3f(1.4f), new AxisAngle4f(0f, 0f, 0f, 1f)));
            disp.setInterpolationDelay(0);

            w.spawnParticle(Particle.ELECTRIC_SPARK, pos, 6, 0.25, 0.25, 0.25, 0.05);
            w.spawnParticle(Particle.END_ROD, pos, 1, 0.1, 0.1, 0.1, 0.0);
            if (life % 6 == 0) w.playSound(pos, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.6f);

            for (LivingEntity e : w.getNearbyLivingEntities(pos, 1.6)) {
                if (e.equals(p) || e instanceof ArmorStand) continue;
                Set<UUID> set = returning ? hitBack : hitOut;
                if (!set.add(e.getUniqueId())) continue;
                if (!returning) {
                    strike(e.getLocation(), p, 2.5, lightningDamage());
                } else {
                    smite(p, e, cfg("throw.return-damage", 8.0));
                }
            }
        }

        /** Gives the hammer back (or flags it to return on respawn if the owner died). */
        void end(boolean ownerDied) {
            if (done) return;
            done = true;
            cancel();
            disp.remove();
            throwing.remove(p.getUniqueId());

            if (ownerDied || p.isDead()) {
                p.getPersistentDataContainer().set(plugin.pendingKey, PersistentDataType.BYTE, (byte) 1);
                return;
            }
            PlayerInventory inv = p.getInventory();
            ItemStack cur = inv.getItem(slot);
            if (cur == null || cur.getType().isAir()) {
                inv.setItem(slot, stack);
            } else {
                for (ItemStack left : inv.addItem(stack).values()) {
                    p.getWorld().dropItem(p.getLocation(), left);
                }
            }
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1.2f, 1.0f);
            p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1.2, 0), 25, 0.3, 0.3, 0.3, 0.2);
        }
    }

    // ----------------------------------------------------------------- cleanup

    public void cleanup(Player p, boolean died) {
        UUID id = p.getUniqueId();
        ThrowTask tt = throwing.get(id);
        if (tt != null) tt.end(died);
        stopFlight(p);
        shielded.remove(id);
        charging.remove(id);
    }

    public void shutdown() {
        for (ThrowTask tt : new ArrayList<>(throwing.values())) tt.end(false);
        for (UUID id : new ArrayList<>(flights.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) stopFlight(p);
        }
        shielded.clear();
        charging.clear();
    }
}
