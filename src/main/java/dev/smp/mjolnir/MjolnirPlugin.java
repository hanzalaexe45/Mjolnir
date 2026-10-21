package dev.smp.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MjolnirPlugin extends JavaPlugin implements TabExecutor {

    public NamespacedKey hammerKey;
    public NamespacedKey abilityKey;
    public NamespacedKey pendingKey;
    public Abilities abilities;

    /** Set while the plugin itself calls LivingEntity#damage, so listeners ignore it. */
    public boolean internalDamage = false;
    /** After an internal damage call: true if some plugin (claims, PvP-off...) cancelled it. */
    public boolean internalBlocked = false;

    private final Set<UUID> worthy = new HashSet<>();
    private final Map<UUID, EnumMap<Ability, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Long> meleeCooldown = new HashMap<>();
    private final Map<UUID, Long> rejectThrottle = new HashMap<>();

    private static final List<PotionEffectType> BAD_EFFECTS = List.of(
            PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.BLINDNESS,
            PotionEffectType.NAUSEA, PotionEffectType.WEAKNESS, PotionEffectType.SLOWNESS,
            PotionEffectType.MINING_FATIGUE, PotionEffectType.DARKNESS, PotionEffectType.HUNGER);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hammerKey = new NamespacedKey(this, "mjolnir");
        abilityKey = new NamespacedKey(this, "ability");
        pendingKey = new NamespacedKey(this, "pending_hammer");
        loadWorthy();
        abilities = new Abilities(this);
        getServer().getPluginManager().registerEvents(new MjolnirListener(this), this);

        PluginCommand cmd = getCommand("mjolnir");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    tickPlayer(p);
                }
            }
        }.runTaskTimer(this, 20L, 10L);
    }

    @Override
    public void onDisable() {
        if (abilities != null) abilities.shutdown();
    }

    // ------------------------------------------------------------------ item

    public ItemStack createHammer() {
        ItemStack it = new ItemStack(Material.MACE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Mjölnir", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Whosoever holds this hammer,", NamedTextColor.GRAY));
        lore.add(line("if he be worthy, shall possess", NamedTextColor.GRAY));
        lore.add(line("the power of Thor.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(line("Right-click: use ability", NamedTextColor.YELLOW));
        lore.add(line("Sneak + right-click: change ability", NamedTextColor.YELLOW));
        lore.add(Component.empty());
        lore.add(line("Undestructible • Always returns", NamedTextColor.DARK_AQUA));
        m.lore(lore);
        m.setUnbreakable(true);
        m.setEnchantmentGlintOverride(true);
        m.setItemModel(new NamespacedKey("mjolnir", "mjolnir"));

        double damage = getConfig().getDouble("hammer.attack-damage", 12.0) - 1.0;
        double speed = getConfig().getDouble("hammer.attack-speed", 1.8) - 4.0;
        m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                new NamespacedKey(this, "mjolnir_damage"), damage,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                new NamespacedKey(this, "mjolnir_speed"), speed,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));

        m.getPersistentDataContainer().set(hammerKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    public boolean isHammer(ItemStack s) {
        if (s == null || s.getType() != Material.MACE || !s.hasItemMeta()) return false;
        return s.getItemMeta().getPersistentDataContainer().has(hammerKey, PersistentDataType.BYTE);
    }

    public boolean holdsHammer(Player p) {
        return isHammer(p.getInventory().getItemInMainHand());
    }

    public void giveHammer(Player p) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(createHammer());
        for (ItemStack i : left.values()) {
            p.getWorld().dropItem(p.getLocation(), i);
        }
    }

    public void givePending(Player p) {
        if (!p.isOnline() || p.isDead()) return;
        if (p.getPersistentDataContainer().has(pendingKey, PersistentDataType.BYTE)) {
            p.getPersistentDataContainer().remove(pendingKey);
            giveHammer(p);
            p.getWorld().strikeLightningEffect(p.getLocation());
            p.sendActionBar(Component.text("Mjölnir returns to your hand.", NamedTextColor.AQUA));
        }
    }

    // -------------------------------------------------------------- worthiness

    public boolean isWorthy(Player p) {
        return getConfig().getBoolean("everyone-worthy", false)
                || p.isOp()
                || p.hasPermission("mjolnir.worthy")
                || worthy.contains(p.getUniqueId());
    }

    private void loadWorthy() {
        worthy.clear();
        for (String s : getConfig().getStringList("worthy-players")) {
            try {
                worthy.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveWorthy() {
        List<String> out = new ArrayList<>();
        for (UUID u : worthy) out.add(u.toString());
        getConfig().set("worthy-players", out);
        saveConfig();
    }

    public void reject(Player p) {
        long now = System.currentTimeMillis();
        Long last = rejectThrottle.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        rejectThrottle.put(p.getUniqueId(), now);
        p.sendActionBar(Component.text("Thou art not worthy.", NamedTextColor.RED, TextDecoration.BOLD));
        p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.6f, 1.4f);
        p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.1);
    }

    // ---------------------------------------------------------------- abilities

    public Ability selected(Player p) {
        String s = p.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        if (s == null) return Ability.THROW;
        try {
            return Ability.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Ability.THROW;
        }
    }

    public void cycle(Player p) {
        Ability next = selected(p).next();
        p.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, next.name());
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
        p.sendActionBar(Component.text("⚡ Ability: ", NamedTextColor.GOLD)
                .append(Component.text(next.display, NamedTextColor.AQUA, TextDecoration.BOLD)));
    }

    public long remainingMs(Player p, Ability a) {
        EnumMap<Ability, Long> map = cooldowns.get(p.getUniqueId());
        if (map == null) return 0;
        Long until = map.get(a);
        if (until == null) return 0;
        return Math.max(0, until - System.currentTimeMillis());
    }

    public void startCooldown(Player p, Ability a) {
        double sec = getConfig().getDouble("abilities." + a.key + ".cooldown", a.defaultCooldown);
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Ability.class))
                .put(a, System.currentTimeMillis() + (long) (sec * 1000));
    }

    public boolean meleeReady(Player p) {
        long now = System.currentTimeMillis();
        Long until = meleeCooldown.get(p.getUniqueId());
        if (until != null && now < until) return false;
        meleeCooldown.put(p.getUniqueId(),
                now + (long) (getConfig().getDouble("melee-lightning.cooldown-seconds", 1.5) * 1000));
        return true;
    }

    // -------------------------------------------------------------- passive tick

    private void tickPlayer(Player p) {
        PlayerInventory inv = p.getInventory();

        // Unworthy players cannot keep the hammer: it falls out of their inventory.
        if (!isWorthy(p)) {
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (isHammer(s)) {
                    inv.setItem(i, null);
                    p.getWorld().dropItemNaturally(p.getLocation(), s);
                    reject(p);
                }
            }
            return;
        }

        boolean main = holdsHammer(p);
        if (!main && !isHammer(inv.getItemInOffHand())) return;

        // Passive: spells and curses are negated, fire cannot hurt the wielder.
        for (PotionEffectType t : BAD_EFFECTS) {
            if (p.hasPotionEffect(t)) p.removePotionEffect(t);
        }
        if (p.getFireTicks() > 0) p.setFireTicks(0);

        if (main && !abilities.isThrowing(p.getUniqueId())) {
            Ability a = selected(p);
            long rem = remainingMs(p, a);
            Component bar = Component.text("⚡ ", NamedTextColor.GOLD)
                    .append(Component.text(a.display, NamedTextColor.AQUA, TextDecoration.BOLD));
            if (rem > 0) {
                bar = bar.append(Component.text("  " + (int) Math.ceil(rem / 1000.0) + "s", NamedTextColor.RED));
            } else {
                bar = bar.append(Component.text("  READY", NamedTextColor.GREEN));
            }
            p.sendActionBar(bar);
        }
    }

    // ----------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String first = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        if (first.equals("help") || first.equals("abilities") || first.equals("?")) {
            sendHelp(s);
            return true;
        }
        if (!s.hasPermission("mjolnir.admin")) {
            s.sendMessage(Component.text("You do not have permission. Use /mjolnir help.", NamedTextColor.RED));
            return true;
        }
        switch (first) {
            case "give" -> {
                Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player p ? p : null);
                if (target == null) {
                    s.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                worthy.add(target.getUniqueId());
                saveWorthy();
                giveHammer(target);
                target.getWorld().strikeLightningEffect(target.getLocation());
                s.sendMessage(Component.text("Gave Mjölnir to " + target.getName()
                        + " (they are now worthy).", NamedTextColor.AQUA));
            }
            case "worthy" -> {
                if (a.length < 2) {
                    s.sendMessage(Component.text("/mjolnir worthy <add|remove|list> [player]", NamedTextColor.YELLOW));
                    return true;
                }
                String sub = a[1].toLowerCase(Locale.ROOT);
                if (sub.equals("list")) {
                    List<String> names = new ArrayList<>();
                    for (UUID u : worthy) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                        names.add(op.getName() != null ? op.getName() : u.toString());
                    }
                    s.sendMessage(Component.text("Worthy: " + (names.isEmpty() ? "(none)" : String.join(", ", names)),
                            NamedTextColor.AQUA));
                    return true;
                }
                if (a.length < 3) {
                    s.sendMessage(Component.text("Specify a player.", NamedTextColor.RED));
                    return true;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[2]);
                if (sub.equals("add")) {
                    worthy.add(op.getUniqueId());
                    s.sendMessage(Component.text(a[2] + " is now worthy.", NamedTextColor.AQUA));
                } else if (sub.equals("remove")) {
                    worthy.remove(op.getUniqueId());
                    s.sendMessage(Component.text(a[2] + " is no longer worthy.", NamedTextColor.AQUA));
                } else {
                    s.sendMessage(Component.text("Use add, remove or list.", NamedTextColor.RED));
                    return true;
                }
                saveWorthy();
            }
            case "reload" -> {
                reloadConfig();
                loadWorthy();
                s.sendMessage(Component.text("Mjölnir config reloaded.", NamedTextColor.AQUA));
            }
            default -> s.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }


    private void sendHelp(CommandSender s) {
        NamedTextColor head = NamedTextColor.AQUA;
        s.sendMessage(Component.text("━━━━━━ ⚡ Mjölnir Help ⚡ ━━━━━━", head, TextDecoration.BOLD));
        s.sendMessage(Component.text("Only the worthy can lift Mjölnir. It is undestructible and always returns to you.",
                NamedTextColor.GRAY));
        s.sendMessage(Component.text("Right-click", NamedTextColor.YELLOW)
                .append(Component.text(" – use the selected ability", NamedTextColor.GRAY)));
        s.sendMessage(Component.text("Sneak + right-click", NamedTextColor.YELLOW)
                .append(Component.text(" – switch to the next ability", NamedTextColor.GRAY)));
        s.sendMessage(Component.text("Fully charged melee hit", NamedTextColor.YELLOW)
                .append(Component.text(" – calls lightning on your target", NamedTextColor.GRAY)));
        s.sendMessage(Component.text("Abilities (in switch order):", head));
        String[] desc = {
                "Throw the hammer; it strikes lightning and flies back to your hand",
                "Call lightning where you aim (kills a full-armor player in 2 strikes)",
                "Summon a thunderstorm that smites nearby enemies",
                "Fly at high speed for a few seconds, no fall damage",
                "Charge a huge shockwave; costs some of your health",
                "Become invulnerable and repel mobs and projectiles",
                "Teleport to where you are looking in a rainbow beam",
                "Reveal every being nearby, even invisible ones"
        };
        Ability[] all = Ability.values();
        for (int i = 0; i < all.length; i++) {
            double cd = getConfig().getDouble("abilities." + all[i].key + ".cooldown", all[i].defaultCooldown);
            s.sendMessage(Component.text(" " + (i + 1) + ". ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(all[i].display, NamedTextColor.GOLD))
                    .append(Component.text(" (" + (int) cd + "s) ", NamedTextColor.DARK_AQUA))
                    .append(Component.text("– " + desc[i], NamedTextColor.GRAY)));
        }
        s.sendMessage(Component.text("Passive: ", head)
                .append(Component.text("absorbs lightning, immune to fire, cleanses curses and bad potion effects.",
                        NamedTextColor.GRAY)));
        if (s.hasPermission("mjolnir.admin")) {
            s.sendMessage(Component.text("Admin commands:", head));
            s.sendMessage(Component.text(" /mjolnir give [player]", NamedTextColor.YELLOW)
                    .append(Component.text(" – give the hammer (also makes them worthy)", NamedTextColor.GRAY)));
            s.sendMessage(Component.text(" /mjolnir worthy <add|remove|list> [player]", NamedTextColor.YELLOW)
                    .append(Component.text(" – manage who is worthy", NamedTextColor.GRAY)));
            s.sendMessage(Component.text(" /mjolnir reload", NamedTextColor.YELLOW)
                    .append(Component.text(" – reload config.yml", NamedTextColor.GRAY)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("mjolnir.admin")) {
            if (a.length == 1 && "help".startsWith(a[0].toLowerCase(Locale.ROOT))) out.add("help");
            return out;
        }
        if (a.length == 1) {
            for (String o : List.of("help", "give", "worthy", "reload")) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("worthy")) {
            for (String o : List.of("add", "remove", "list")) {
                if (o.startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(o);
            }
        } else if ((a.length == 2 && a[0].equalsIgnoreCase("give"))
                || (a.length == 3 && a[0].equalsIgnoreCase("worthy"))) {
            String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
