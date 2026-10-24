package dev.mix.xenon.listener;

import dev.mix.xenon.monitor.PerformanceMonitor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

public final class ChunkActivityListener implements Listener {
    private final PerformanceMonitor monitor;

    public ChunkActivityListener(PerformanceMonitor monitor) { this.monitor = monitor; }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        monitor.recordChunkLoad(event.isNewChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        monitor.recordChunkUnload();
    }
}
