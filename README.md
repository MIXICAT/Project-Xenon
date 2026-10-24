# Xenon 1.0.0

A conservative Paper server optimization plugin focused on loaded-entity cleanup, mob spawn limits, spawn-area chunk pregeneration, and performance monitoring.

## Target

- Minecraft / Paper: 26.3
- Java: 25
- Build: Gradle Kotlin DSL
- Plugin entry point: `dev.mix.xenon.XenonPlugin`

## Features

### 1. Entity visibility cleanup

Every configured interval, Xenon checks a bounded number of loaded mobs. It approximates visibility using a player's camera view cone plus Bukkit's line-of-sight check. If an eligible mob has not been detected by any player's view for five minutes, it is removed.

By default, this targets hostile monsters only. Passive mobs are protected unless `entity-cleanup.include-passive-mobs` is enabled. Xenon also protects mobs configured not to despawn naturally, named mobs, tamed mobs, leashed mobs, mobs with passengers or vehicles, and mobs currently targeting something. Villagers and players are not targeted.

**Important limitation:** Bukkit/Paper does not expose a perfect, cheap test for whether a mob is literally visible on the rendered screen. The plugin's view-cone and line-of-sight check is an approximation. To limit overhead, scans are periodic and capped by `max-entity-checks-per-run`. Very large entity populations may take multiple scans to be evaluated.

### 2. Automatic spawn-area pregeneration

When enabled, Xenon waits the configured startup delay and starts one controlled pregeneration job around the spawn of the first eligible loaded world. Radius 8 means a square of 17 x 17 = 289 chunks. The job requests one chunk at a time and pauses when observed tick intervals or heap usage exceed configured thresholds.

`pregen.startup.worlds: []` means the first loaded world. To target specific worlds, list their exact names. Xenon starts only one startup job; use `/xenon pregen` to manually pre-generate other worlds.

### 3. Mob spawn limits

Xenon applies per-world limits to currently loaded hostile mobs, passive animals, and water mobs. Counts are cached and refreshed every 10 seconds instead of scanning the world for every spawn event. Accepted spawn events increment the cache; deaths decrement it. The periodic refresh corrects drift from other entity lifecycle events.

These limits are separate from Minecraft's native per-player mob caps and may reduce natural spawning when reached. Spawner spawns are exempt by default. Set a category limit to `0` to disable Xenon's limit for that category.

### 4. Performance reporting

Reports include Paper TPS samples, observed tick intervals, normalized process CPU estimate where available, heap use, GC activity, and chunk load/generation/unload counters. Observed tick interval is not a precise measurement of internal tick execution time; use a profiler such as spark for detailed attribution.

## Commands

- `/xenon status` - status and current performance metrics
- `/xenon report` - performance report
- `/xenon pregen <world> <centerChunkX> <centerChunkZ> <radius>` - start manual pregeneration
- `/xenon cancel` - cancel the active pregeneration job
- `/xenon refreshmobs` - refresh cached mob counts
- `/xenon reload` - reload Xenon configuration

Permissions:

- `xenon.status` (default: everyone)
- `xenon.admin` (default: operators)

## Build

Use JDK 25 and a compatible Gradle installation:

```bash
gradle clean build
```

The plugin JAR will be generated under `build/libs/`.

## Safety notes

- Back up the world before first use.
- Test on a staging server before production.
- Startup pregeneration consumes CPU and disk I/O; configure a small radius first.
- Entity cleanup removes eligible mobs after its timer. It does not remove player-owned or persistent entities under the default rules.
- This plugin cannot directly improve a player's GPU, increase physical CPU performance, or add RAM. It can reduce server-side workload.
- The source has not been compiled or runtime-tested in the current environment. The available environment uses Java 21 and has no Gradle installation; build and test with Java 25 before deployment.
