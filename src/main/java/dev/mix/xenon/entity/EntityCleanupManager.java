package dev.mix.xenon.entity;

import dev.mix.xenon.XenonPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Periodically checks loaded eligible mobs. Visibility is approximated by camera view cone plus line of sight. */
public final class EntityCleanupManager implements Listener {
    private final XenonPlugin plugin;
    private final Map<UUID, Long> unseenSince = new HashMap<>();
    private BukkitTask task;
    private long unseenMillis;
    private long intervalTicks;
    private boolean enabled;
    private boolean includePassive;
    private int maxChecksPerRun;
    private double viewConeDot;
    private long removed;
    private long protectedCount;

    public EntityCleanupManager(XenonPlugin plugin) {
        this.plugin = plugin;
        applyConfig();
    }

    public void applyConfig() {
        enabled = plugin.getConfig().getBoolean("entity-cleanup.enabled", true);
        unseenMillis = Math.max(60_000L, plugin.getConfig().getLong("entity-cleanup.unseen-minutes", 5L) * 60_000L);
        intervalTicks = Math.max(100L, plugin.getConfig().getLong("entity-cleanup.interval-ticks", 600L));
        includePassive = plugin.getConfig().getBoolean("entity-cleanup.include-passive-mobs", false);
        maxChecksPerRun = Math.max(100, plugin.getConfig().getInt("entity-cleanup.max-entity-checks-per-run", 2500));
        double degrees = Math.max(30.0, Math.min(180.0, plugin.getConfig().getDouble("entity-cleanup.view-cone-degrees", 110.0)));
        viewConeDot = Math.cos(Math.toRadians(degrees / 2.0));
        if (!enabled && task != null) { task.cancel(); task = null; }
    }

    public void start() {
        if (task != null) task.cancel();
        if (enabled) task = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, intervalTicks, intervalTicks);
    }

    public void stop() { if (task != null) task.cancel(); task = null; unseenSince.clear(); }

    private void scan() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        int checks = 0;
        outer: for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob mob) || !eligible(mob)) continue;
                if (++checks > maxChecksPerRun) break outer;
                UUID id = mob.getUniqueId();
                if (isVisibleToAnyPlayer(mob)) {
                    unseenSince.remove(id);
                    continue;
                }
                long since = unseenSince.computeIfAbsent(id, ignored -> now);
                if (now - since >= unseenMillis) {
                    mob.remove();
                    unseenSince.remove(id);
                    removed++;
                }
            }
        }
        // Keep the tracking map bounded when mobs die or unload between scans.
        unseenSince.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
    }

    private boolean eligible(Mob mob) {
        if (!mob.isValid() || mob.isDead() || !mob.getRemoveWhenFarAway() || mob.getCustomName() != null || mob.isLeashed()) return false;
        if (mob instanceof Tameable tameable && tameable.isTamed()) return false;
        if (mob.getPassengers().size() > 0 || mob.getVehicle() != null) return false;
        if (mob instanceof Animals && !includePassive) return false;
        if (!(mob instanceof Monster) && !includePassive) return false;
        if (mob.getTarget() != null) return false;
        return true;
    }

    private boolean isVisibleToAnyPlayer(Mob mob) {
        Location entityEye = mob.getEyeLocation();
        for (Player player : mob.getWorld().getPlayers()) {
            if (!player.isOnline() || player.getGameMode().name().equals("SPECTATOR")) continue;
            Location eye = player.getEyeLocation();
            Vector toEntity = entityEye.toVector().subtract(eye.toVector());
            double distanceSquared = toEntity.lengthSquared();
            if (distanceSquared < 0.01) return true;
            Vector direction = player.getLocation().getDirection();
            if (direction.dot(toEntity.normalize()) < viewConeDot) continue;
            // The line-of-sight check is only done for mobs inside the player's view cone.
            if (player.hasLineOfSight(mob)) return true;
        }
        return false;
    }

    @EventHandler public void onDeath(EntityDeathEvent event) { unseenSince.remove(event.getEntity().getUniqueId()); }

    @EventHandler public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) unseenSince.remove(entity.getUniqueId());
    }

    public long getRemoved() { return removed; }
    public int getTrackedUnseen() { return unseenSince.size(); }
    public boolean isEnabled() { return enabled; }
}
