package dev.mix.xenon;

import dev.mix.xenon.command.XenonCommand;
import dev.mix.xenon.entity.EntityCleanupManager;
import dev.mix.xenon.entity.MobSpawnController;
import dev.mix.xenon.listener.ChunkActivityListener;
import dev.mix.xenon.monitor.PerformanceMonitor;
import dev.mix.xenon.pregen.ChunkPregenerator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class XenonPlugin extends JavaPlugin {
    private PerformanceMonitor performanceMonitor;
    private ChunkPregenerator chunkPregenerator;
    private EntityCleanupManager entityCleanupManager;
    private MobSpawnController mobSpawnController;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        performanceMonitor = new PerformanceMonitor(this);
        chunkPregenerator = new ChunkPregenerator(this, performanceMonitor);
        entityCleanupManager = new EntityCleanupManager(this);
        mobSpawnController = new MobSpawnController(this);

        getServer().getPluginManager().registerEvents(new ChunkActivityListener(performanceMonitor), this);
        getServer().getPluginManager().registerEvents(entityCleanupManager, this);
        getServer().getPluginManager().registerEvents(mobSpawnController, this);
        performanceMonitor.start();
        chunkPregenerator.start();
        entityCleanupManager.start();
        mobSpawnController.start();

        PluginCommand command = getCommand("xenon");
        if (command == null) {
            getLogger().severe("Command 'xenon' is missing from plugin.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        XenonCommand executor = new XenonCommand(this, performanceMonitor, chunkPregenerator, entityCleanupManager, mobSpawnController);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        scheduleStartupPregeneration();
        getLogger().info("Xenon 1.0.0 enabled. Entity cleanup and spawn caps are configurable; spawn-area pregeneration starts automatically when enabled.");
    }

    private void scheduleStartupPregeneration() {
        if (!getConfig().getBoolean("pregen.startup.enabled", true)) return;
        long delay = Math.max(20L, getConfig().getLong("pregen.startup.delay-ticks", 100L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!isEnabled()) return;
            for (World world : Bukkit.getWorlds()) {
                if (!getConfig().getStringList("pregen.startup.worlds").isEmpty()
                        && !getConfig().getStringList("pregen.startup.worlds").contains(world.getName())) continue;
                int radius = getConfig().getInt("pregen.startup.radius-chunks", 8);
                if (chunkPregenerator.begin(world, world.getSpawnLocation().getChunk().getX(),
                        world.getSpawnLocation().getChunk().getZ(), radius)) {
                    getLogger().info("Startup pregeneration queued for world '" + world.getName() + "' around spawn, radius " + radius + " chunks.");
                    break; // One controlled job at a time. Other worlds can be started with /xenon pregen.
                }
            }
        }, delay);
    }

    @Override
    public void onDisable() {
        if (entityCleanupManager != null) entityCleanupManager.stop();
        if (mobSpawnController != null) mobSpawnController.stop();
        if (chunkPregenerator != null) chunkPregenerator.stop(true);
        if (performanceMonitor != null) performanceMonitor.stop();
    }

    public PerformanceMonitor getPerformanceMonitor() { return performanceMonitor; }
    public ChunkPregenerator getChunkPregenerator() { return chunkPregenerator; }
    public EntityCleanupManager getEntityCleanupManager() { return entityCleanupManager; }
    public MobSpawnController getMobSpawnController() { return mobSpawnController; }

    public void reloadPluginConfig() {
        reloadConfig();
        performanceMonitor.applyConfig();
        performanceMonitor.restartSummaryTask();
        chunkPregenerator.applyConfig();
        chunkPregenerator.start();
        entityCleanupManager.applyConfig();
        mobSpawnController.applyConfig();
        mobSpawnController.refreshCounts();
    }
}
