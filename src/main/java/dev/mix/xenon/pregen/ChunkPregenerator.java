package dev.mix.xenon.pregen;

import dev.mix.xenon.XenonPlugin;
import dev.mix.xenon.monitor.PerformanceMonitor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

/** Sequential, opt-in pregeneration. Only one async chunk request is in flight at a time. */
public final class ChunkPregenerator {
    private final XenonPlugin plugin;
    private final PerformanceMonitor monitor;
    private final Queue<ChunkCoordinate> queue = new ArrayDeque<>();
    private BukkitTask runner;
    private boolean enabled;
    private int maxRadius;
    private int intervalTicks;
    private double pauseTickMs;
    private int pauseHeapPercent;
    private boolean active;
    private boolean requestInFlight;
    private UUID worldId;
    private String worldName;
    private long completed;
    private long failed;
    private long total;
    private long startedAt;
    private long jobGeneration;

    public ChunkPregenerator(XenonPlugin plugin, PerformanceMonitor monitor) {
        this.plugin = plugin;
        this.monitor = monitor;
        applyConfig();
    }

    public void applyConfig() {
        enabled = plugin.getConfig().getBoolean("pregen.enabled", true);
        maxRadius = Math.max(1, Math.min(64, plugin.getConfig().getInt("pregen.max-radius", 16)));
        intervalTicks = Math.max(1, plugin.getConfig().getInt("pregen.request-interval-ticks", 2));
        pauseTickMs = Math.max(20.0, plugin.getConfig().getDouble("pregen.pause-average-tick-ms", 60.0));
        pauseHeapPercent = Math.max(0, Math.min(100, plugin.getConfig().getInt("pregen.pause-heap-percent", 92)));
    }

    public void start() {
        if (runner != null) runner.cancel();
        runner = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, intervalTicks);
    }

    public void stop(boolean announce) {
        if (runner != null) runner.cancel();
        runner = null;
        if (active && announce) plugin.getLogger().info("Stopping active chunk pregeneration during plugin shutdown.");
        queue.clear();
        active = false;
        requestInFlight = false;
    }

    public boolean begin(World world, int centerX, int centerZ, int radius) {
        if (!enabled || active || world == null || radius < 1 || radius > maxRadius) return false;
        queue.clear();
        // Spiral-ish square order from center outward to prioritize nearby chunks.
        for (int ring = 0; ring <= radius; ring++) {
            for (int x = -ring; x <= ring; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != ring) continue;
                    queue.add(new ChunkCoordinate(centerX + x, centerZ + z));
                }
            }
        }
        jobGeneration++;
        worldId = world.getUID();
        worldName = world.getName();
        completed = 0L;
        failed = 0L;
        total = queue.size();
        startedAt = System.currentTimeMillis();
        active = true;
        requestInFlight = false;
        return true;
    }

    public boolean cancel() {
        if (!active) return false;
        active = false;
        jobGeneration++;
        queue.clear();
        // A request already handed to Paper cannot safely be cancelled; its callback is ignored.
        requestInFlight = false;
        return true;
    }

    private void tick() {
        if (!active || requestInFlight) return;
        if (queue.isEmpty()) {
            active = false;
            plugin.getLogger().info("Chunk pregeneration completed for world '" + worldName + "': " + completed + "/" + total + " chunks, " + failed + " failures.");
            return;
        }
        if (monitor.getAverageTickIntervalMs() > pauseTickMs) return;
        if (pauseHeapPercent > 0 && monitor.getHeapPercent() >= pauseHeapPercent) return;

        World world = Bukkit.getWorld(worldId);
        if (world == null) {
            plugin.getLogger().warning("Chunk pregeneration stopped: world '" + worldName + "' is no longer loaded.");
            cancel();
            return;
        }
        ChunkCoordinate coordinate = queue.poll();
        if (coordinate == null) return;
        requestInFlight = true;
        long thisJob = jobGeneration;
        try {
            // Paper documents this future's completion as synchronous on the main server thread.
            world.getChunkAtAsync(coordinate.x, coordinate.z, true, false).whenComplete((chunk, throwable) -> {
                if (!active || thisJob != jobGeneration) return;
                requestInFlight = false;
                if (throwable != null || chunk == null) failed++;
                else completed++;
                if (completed + failed == total) {
                    active = false;
                    long seconds = Math.max(1L, (System.currentTimeMillis() - startedAt) / 1000L);
                    plugin.getLogger().info("Chunk pregeneration finished for world '" + worldName + "': " + completed + "/" + total + " succeeded, " + failed + " failed in " + seconds + "s.");
                }
            });
        } catch (RuntimeException exception) {
            requestInFlight = false;
            failed++;
            plugin.getLogger().warning("Could not request chunk " + coordinate.x + ", " + coordinate.z + ": " + exception.getMessage());
        }
    }

    public String getStatus() {
        if (!active) return "Idle";
        return "Running world=" + worldName + ", completed=" + completed + ", failed=" + failed + ", pending=" + queue.size() + ", total=" + total + ", pausedByLoad=" + (monitor.getAverageTickIntervalMs() > pauseTickMs || (pauseHeapPercent > 0 && monitor.getHeapPercent() >= pauseHeapPercent));
    }

    public boolean isActive() { return active; }
    public boolean isEnabled() { return enabled; }
    public int getMaxRadius() { return maxRadius; }

    private record ChunkCoordinate(int x, int z) { }
}
