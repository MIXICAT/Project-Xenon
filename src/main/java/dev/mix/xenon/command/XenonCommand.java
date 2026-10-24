package dev.mix.xenon.command;

import dev.mix.xenon.XenonPlugin;
import dev.mix.xenon.entity.EntityCleanupManager;
import dev.mix.xenon.entity.MobSpawnController;
import dev.mix.xenon.monitor.PerformanceMonitor;
import dev.mix.xenon.pregen.ChunkPregenerator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class XenonCommand implements CommandExecutor, TabCompleter {
    private final XenonPlugin plugin;
    private final PerformanceMonitor monitor;
    private final ChunkPregenerator pregenerator;
    private final EntityCleanupManager cleanup;
    private final MobSpawnController spawnController;

    public XenonCommand(XenonPlugin plugin, PerformanceMonitor monitor, ChunkPregenerator pregenerator,
                        EntityCleanupManager cleanup, MobSpawnController spawnController) {
        this.plugin = plugin;
        this.monitor = monitor;
        this.pregenerator = pregenerator;
        this.cleanup = cleanup;
        this.spawnController = spawnController;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (!sender.hasPermission("xenon.status")) return deny(sender);
            sender.sendMessage(color("&6&lXenon &7v" + plugin.getPluginMeta().getVersion()));
            sender.sendMessage(color("&7" + monitor.buildReport()));
            sender.sendMessage(color("&7Pregeneration: &f" + pregenerator.getStatus()));
            sender.sendMessage(color("&7Entity cleanup: &fenabled=" + cleanup.isEnabled() + ", removed=" + cleanup.getRemoved() + ", currently tracked unseen=" + cleanup.getTrackedUnseen()));
            sender.sendMessage(color("&7Mob spawn controller: &f" + spawnController.getStatus()));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "report" -> {
                if (!sender.hasPermission("xenon.status")) return deny(sender);
                sender.sendMessage(color("&6&lXenon performance report"));
                sender.sendMessage(color("&7" + monitor.buildReport()));
                sender.sendMessage(color("&7Tick interval is an observed scheduler interval, not direct internal tick execution time."));
                sender.sendMessage(color("&7Cleanup removed mobs: " + cleanup.getRemoved()));
                sender.sendMessage(color("&7Spawn control: " + spawnController.getStatus()));
                return true;
            }
            case "pregen" -> {
                if (!sender.hasPermission("xenon.admin")) return deny(sender);
                if (args.length != 5) {
                    sender.sendMessage(color("&cUsage: /" + label + " pregen <world> <centerChunkX> <centerChunkZ> <radius>"));
                    sender.sendMessage(color("&7Example: /" + label + " pregen world 0 0 8"));
                    return true;
                }
                World world = Bukkit.getWorld(args[1]);
                if (world == null) { sender.sendMessage(color("&cWorld not found or not loaded: " + args[1])); return true; }
                try {
                    int centerX = Integer.parseInt(args[2]);
                    int centerZ = Integer.parseInt(args[3]);
                    int radius = Integer.parseInt(args[4]);
                    if (radius < 1 || radius > pregenerator.getMaxRadius()) {
                        sender.sendMessage(color("&cRadius must be between 1 and " + pregenerator.getMaxRadius() + " chunks.")); return true;
                    }
                    long count = (2L * radius + 1L) * (2L * radius + 1L);
                    if (pregenerator.begin(world, centerX, centerZ, radius)) {
                        sender.sendMessage(color("&aStarted controlled pregeneration: " + count + " chunks around chunk " + centerX + ", " + centerZ + " in " + world.getName() + "."));
                        sender.sendMessage(color("&eChunk generation uses CPU and disk I/O; it pauses when configured load thresholds are exceeded."));
                    } else sender.sendMessage(color("&cCould not start. Check pregeneration is enabled and no job is running."));
                } catch (NumberFormatException exception) { sender.sendMessage(color("&cChunk coordinates and radius must be whole numbers.")); }
                return true;
            }
            case "cancel" -> {
                if (!sender.hasPermission("xenon.admin")) return deny(sender);
                sender.sendMessage(color(pregenerator.cancel() ? "&aPregeneration cancelled. A submitted chunk request may finish." : "&eNo pregeneration job is active."));
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("xenon.admin")) return deny(sender);
                plugin.reloadPluginConfig();
                sender.sendMessage(color("&aXenon configuration reloaded."));
                return true;
            }
            case "refreshmobs" -> {
                if (!sender.hasPermission("xenon.admin")) return deny(sender);
                spawnController.refreshCounts();
                sender.sendMessage(color("&aMob counts refreshed."));
                return true;
            }
            default -> { sender.sendMessage(color("&eUsage: /" + label + " <status|report|pregen|cancel|reload|refreshmobs>")); return true; }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return startsWith(List.of("status", "report", "pregen", "cancel", "reload", "refreshmobs"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("pregen")) return startsWith(Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList()), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("pregen") && sender instanceof Player player) return List.of(Integer.toString(player.getLocation().getChunk().getX()));
        if (args.length == 4 && args[0].equalsIgnoreCase("pregen") && sender instanceof Player player) return List.of(Integer.toString(player.getLocation().getChunk().getZ()));
        if (args.length == 5 && args[0].equalsIgnoreCase("pregen")) return startsWith(List.of("4", "8", "12", "16"), args[4]);
        return List.of();
    }

    private static List<String> startsWith(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(value);
        return result;
    }
    private static boolean deny(CommandSender sender) { sender.sendMessage(color("&cYou do not have permission to use this command.")); return true; }
    private static String color(String message) { return ChatColor.translateAlternateColorCodes('&', message); }
}
