package dev.mix.xenon.entity;

import dev.mix.xenon.XenonPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cached loaded-entity counts avoid scanning every world for every spawn event. */
public final class MobSpawnController implements Listener {
    private final XenonPlugin plugin;
    private final Map<String, Counts> countsByWorld = new ConcurrentHashMap<>();
    private BukkitTask refreshTask;
    private boolean enabled;
    private int hostileLimit;
    private int passiveLimit;
    private int waterLimit;
    private boolean limitSpawnerSpawns;
    private long cancelled;

    public MobSpawnController(XenonPlugin plugin) { this.plugin = plugin; applyConfig(); }

    public void applyConfig() {
        enabled = plugin.getConfig().getBoolean("mob-spawn.enabled", true);
        hostileLimit = Math.max(0, plugin.getConfig().getInt("mob-spawn.limits.hostile", 70));
        passiveLimit = Math.max(0, plugin.getConfig().getInt("mob-spawn.limits.passive", 10));
        waterLimit = Math.max(0, plugin.getConfig().getInt("mob-spawn.limits.water", 15));
        limitSpawnerSpawns = plugin.getConfig().getBoolean("mob-spawn.limit-spawners", false);
        if (!enabled && refreshTask != null) { refreshTask.cancel(); refreshTask = null; }
    }

    public void start() {
        if (refreshTask != null) refreshTask.cancel();
        if (enabled) refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshCounts, 20L, 200L);
    }
    public void stop() { if (refreshTask != null) refreshTask.cancel(); refreshTask = null; }

    public void refreshCounts() {
        countsByWorld.clear();
        for (World world : Bukkit.getWorlds()) {
            Counts counts = new Counts();
            for (Entity entity : world.getLivingEntities()) {
                if (!(entity instanceof org.bukkit.entity.Mob)) continue;
                if (entity instanceof Monster) counts.hostile++;
                else if (entity instanceof WaterMob) counts.water++;
                else if (entity instanceof Animals) counts.passive++;
            }
            countsByWorld.put(world.getName(), counts);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!enabled) return;
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (!limitSpawnerSpawns && (reason == CreatureSpawnEvent.SpawnReason.SPAWNER || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG)) return;
        LivingEntity entity = event.getEntity();
        Counts counts = countsByWorld.computeIfAbsent(event.getLocation().getWorld().getName(), ignored -> new Counts());
        if (entity instanceof Monster) {
            if (hostileLimit > 0 && counts.hostile >= hostileLimit) { event.setCancelled(true); cancelled++; }
            else counts.hostile++;
        } else if (entity instanceof WaterMob) {
            if (waterLimit > 0 && counts.water >= waterLimit) { event.setCancelled(true); cancelled++; }
            else counts.water++;
        } else if (entity instanceof Animals) {
            if (passiveLimit > 0 && counts.passive >= passiveLimit) { event.setCancelled(true); cancelled++; }
            else counts.passive++;
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Counts counts = countsByWorld.get(entity.getWorld().getName());
        if (counts == null || !(entity instanceof org.bukkit.entity.Mob)) return;
        if (entity instanceof Monster) counts.hostile = Math.max(0, counts.hostile - 1);
        else if (entity instanceof WaterMob) counts.water = Math.max(0, counts.water - 1);
        else if (entity instanceof Animals) counts.passive = Math.max(0, counts.passive - 1);
    }

    public String getStatus() {
        long hostiles = 0, passives = 0, water = 0;
        for (Counts c : countsByWorld.values()) { hostiles += c.hostile; passives += c.passive; water += c.water; }
        return "enabled=" + enabled + ", loaded hostile/passive/water=" + hostiles + "/" + passives + "/" + water
                + ", limits=" + hostileLimit + "/" + passiveLimit + "/" + waterLimit + ", cancelled=" + cancelled;
    }
    public boolean isEnabled() { return enabled; }

    private static final class Counts { int hostile; int passive; int water; }
}
