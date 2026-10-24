package dev.mix.xenon.monitor;

import dev.mix.xenon.XenonPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** Low-overhead sampler. It measures observed tick intervals, not internal tick execution time. */
public final class PerformanceMonitor {
    private static final int WINDOW_SIZE = 100;
    private final XenonPlugin plugin;
    private final Deque<Double> tickIntervalsMs = new ArrayDeque<>(WINDOW_SIZE);
    private BukkitTask tickSampler;
    private BukkitTask summaryTask;
    private long lastTickNanos;
    private long chunkLoads;
    private long chunkGenerations;
    private long chunkUnloads;
    private long totalGcCollections;
    private long totalGcTimeMs;
    private int heapWarningPercent;
    private double tickWarningMs;
    private int sampleIntervalTicks;
    private boolean logSummary;
    private double lastProcessCpuLoad = -1.0;
    private long lastCpuSampleNanos;
    private long lastProcessCpuNanos;
    private long lastTickWarningNanos;

    public PerformanceMonitor(XenonPlugin plugin) {
        this.plugin = plugin;
        applyConfig();
    }

    public void applyConfig() {
        heapWarningPercent = clamp(plugin.getConfig().getInt("monitoring.heap-warning-percent", 90), 0, 100);
        tickWarningMs = Math.max(0.0, plugin.getConfig().getDouble("monitoring.tick-warning-ms", 75.0));
        sampleIntervalTicks = Math.max(20, plugin.getConfig().getInt("monitoring.sample-interval-ticks", 1200));
        logSummary = plugin.getConfig().getBoolean("monitoring.log-summary", false);
    }

    public void start() {
        lastTickNanos = System.nanoTime();
        tickSampler = Bukkit.getScheduler().runTaskTimer(plugin, this::sampleTickInterval, 1L, 1L);
        summaryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::periodicSample, sampleIntervalTicks, sampleIntervalTicks);
    }

    public void restartSummaryTask() {
        if (summaryTask != null) summaryTask.cancel();
        summaryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::periodicSample, sampleIntervalTicks, sampleIntervalTicks);
    }

    public void stop() {
        if (tickSampler != null) tickSampler.cancel();
        if (summaryTask != null) summaryTask.cancel();
    }

    private void sampleTickInterval() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L) {
            double intervalMs = (now - lastTickNanos) / 1_000_000.0;
            if (Double.isFinite(intervalMs) && intervalMs >= 0.0) {
                if (tickIntervalsMs.size() == WINDOW_SIZE) tickIntervalsMs.removeFirst();
                tickIntervalsMs.addLast(intervalMs);
                if (tickWarningMs > 0.0 && intervalMs >= tickWarningMs) {
                    // Rate-limit warnings so sustained lag does not flood the console.
                    long nowNanos = System.nanoTime();
                    if (nowNanos - lastTickWarningNanos >= 30_000_000_000L) {
                        lastTickWarningNanos = nowNanos;
                        plugin.getLogger().warning(String.format(Locale.ROOT,
                                "Observed tick interval %.1f ms (threshold %.1f ms). Use a profiler to identify the cause.",
                                intervalMs, tickWarningMs));
                    }
                }
            }
        }
        lastTickNanos = now;
    }

    private void periodicSample() {
        updateCpuSample();
        checkHeapWarning();
        if (logSummary) plugin.getLogger().info(buildReport());
    }

    private void updateCpuSample() {
        java.lang.management.OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();
        if (base instanceof com.sun.management.OperatingSystemMXBean bean) {
            long now = System.nanoTime();
            long cpuNow = bean.getProcessCpuTime();
            if (cpuNow < 0L) {
                lastProcessCpuLoad = -1.0;
                return;
            }
            if (lastCpuSampleNanos != 0L && cpuNow >= lastProcessCpuNanos) {
                long elapsed = now - lastCpuSampleNanos;
                if (elapsed > 0L) {
                    double coresUsed = (double) (cpuNow - lastProcessCpuNanos) / elapsed;
                    int processors = Math.max(1, bean.getAvailableProcessors());
                    lastProcessCpuLoad = Math.max(0.0, Math.min(100.0, coresUsed / processors * 100.0));
                }
            }
            lastCpuSampleNanos = now;
            lastProcessCpuNanos = cpuNow;
        }
    }

    private void checkHeapWarning() {
        if (heapWarningPercent <= 0) return;
        MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = usage.getMax();
        if (max <= 0L) return;
        int percent = (int) Math.round(usage.getUsed() * 100.0 / max);
        if (percent >= heapWarningPercent) {
            plugin.getLogger().warning("Java heap usage is " + percent + "% (configured warning threshold " + heapWarningPercent + "%). Check heap sizing and a GC/heap profile; Xenon will not force garbage collection.");
        }
    }

    public synchronized void recordChunkLoad(boolean newChunk) {
        chunkLoads++;
        if (newChunk) chunkGenerations++;
    }

    public synchronized void recordChunkUnload() { chunkUnloads++; }

    public String buildReport() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long heapMax = heap.getMax();
        double heapPercent = heapMax > 0 ? heap.getUsed() * 100.0 / heapMax : 0.0;
        long gcCount = 0L;
        long gcTime = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() > 0) gcTime += gc.getCollectionTime();
        }
        long gcCountDelta = Math.max(0L, gcCount - totalGcCollections);
        long gcTimeDelta = Math.max(0L, gcTime - totalGcTimeMs);
        totalGcCollections = gcCount;
        totalGcTimeMs = gcTime;

        String cpu = lastProcessCpuLoad < 0.0 ? "unavailable" : String.format(Locale.ROOT, "%.1f%% process CPU (normalized)", lastProcessCpuLoad);
        return String.format(Locale.ROOT,
                "Performance | TPS 1m/5m/15m: %.2f / %.2f / %.2f | observed tick interval avg/max: %.1f / %.1f ms | %s | heap: %s / %s (%.1f%%) | GC since previous report: %d collections, %d ms | chunks loaded/generated/unloaded: %d/%d/%d",
                tps(0), tps(1), tps(2), getAverageTickIntervalMs(), getMaxTickIntervalMs(), cpu,
                formatBytes(heap.getUsed()), heapMax > 0 ? formatBytes(heapMax) : "unknown", heapPercent,
                gcCountDelta, gcTimeDelta, getChunkLoads(), getChunkGenerations(), getChunkUnloads());
    }

    private double tps(int index) {
        double[] values = Bukkit.getTPS();
        return values.length > index ? Math.min(20.0, Math.max(0.0, values[index])) : 0.0;
    }

    public synchronized double getAverageTickIntervalMs() {
        if (tickIntervalsMs.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double value : tickIntervalsMs) sum += value;
        return sum / tickIntervalsMs.size();
    }

    public synchronized double getMaxTickIntervalMs() {
        double max = 0.0;
        for (double value : tickIntervalsMs) max = Math.max(max, value);
        return max;
    }

    public double getHeapPercent() {
        MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return usage.getMax() > 0 ? usage.getUsed() * 100.0 / usage.getMax() : 0.0;
    }

    public synchronized long getChunkLoads() { return chunkLoads; }
    public synchronized long getChunkGenerations() { return chunkGenerations; }
    public synchronized long getChunkUnloads() { return chunkUnloads; }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
