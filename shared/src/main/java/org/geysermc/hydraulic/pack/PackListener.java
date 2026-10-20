package org.geysermc.hydraulic.pack;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.geysermc.event.PostOrder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.option.PriorityOption;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.entity.client.EntityGeometryDump;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.hydraulic.storage.ModStorage;
import org.geysermc.hydraulic.util.FormatUtil;
import org.geysermc.hydraulic.util.PackUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import java.util.concurrent.Executors;

/**
 * Listens for events related to packs.
 */
public class PackListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ExecutorService executor;

    private final HydraulicImpl hydraulic;
    private final PackManager manager;

    public PackListener(HydraulicImpl hydraulic, PackManager manager) {
        this.hydraulic = hydraulic;
        this.manager = manager;

        Runtime runtime = Runtime.getRuntime();
        int workers = ConversionWorkers.count(runtime.availableProcessors(), runtime.maxMemory());
        LOGGER.info("Using {} resource-pack conversion worker(s)", workers);
        this.executor = Executors.newFixedThreadPool(workers,
            new ThreadFactoryBuilder()
                .setNameFormat(Constants.MOD_NAME + " Conversion Thread #%d")
                .setUncaughtExceptionHandler((thread, throwable) -> LOGGER.error("Uncaught exception in thread {}", thread.getName(), throwable))
                .build()
        );
        hydraulic.registerServerStop(server -> {
            this.executor.shutdown();
        });
    }

    @Subscribe(postOrder = PostOrder.LATE)
    public void onLoadResourcePacks(GeyserDefineResourcePacksEvent event) {
        long newestGeometry = this.newestEntityGeometry();
        // Check if hydraulic has updated since the last pack conversion
        // This is so we can regenerate packs on update in case the pack generation logic has changed
        ModInfo hydraulicMod = this.hydraulic.mod(Constants.MOD_ID);
        boolean hydraulicUpdated = checkNeedsConversion(hydraulicMod,
                this.hydraulic.modStorage(hydraulicMod).pack(), newestGeometry);

        if (hydraulicUpdated) {
            LOGGER.info("Hydraulic has updated since the last pack conversion, regenerating all packs!");
        }

        // Go over all mods and load the pack or mark them for conversion
        Map<String, Pair<ModInfo, Path>> packsToLoad = new LinkedHashMap<>();
        Map<String, CompletableFuture<Path>> results = new LinkedHashMap<>();
        for (ModInfo mod : this.hydraulic.mods()) {
            if (PackManager.IGNORED_MODS.contains(mod.id())) {
                continue;
            }

            // Ignore generated mods
            if (mod.id().startsWith("generated_")) {
                continue;
            }

            ModStorage storage = this.hydraulic.modStorage(mod);

            Path packPath = storage.pack();
            if (this.hydraulic.isDev() || hydraulicUpdated || checkNeedsConversion(mod, packPath, newestGeometry)) {
                packsToLoad.put(mod.id(), Pair.of(mod, packPath));
                results.put(mod.id(), CompletableFuture.completedFuture(null));
            } else {
                // We don't need to convert the pack, just register it
                LOGGER.info("Registering already converted pack for mod {}", mod.id());
                results.put(mod.id(), CompletableFuture.completedFuture(packPath));
            }
        }

        LOGGER.info("Found {} packs to convert", packsToLoad.size());

        long start = System.currentTimeMillis();

        for (var entry : packsToLoad.entrySet()) {
            results.put(entry.getKey(), CompletableFuture.supplyAsync(() -> {
                LOGGER.info("Converting pack for mod {}", entry.getKey());
                try {
                    if (this.manager.createPack(entry.getValue().getLeft(), entry.getValue().getRight())) {
                        return entry.getValue().getRight();
                    }
                } catch (Throwable t) {
                    LOGGER.error("Failed to convert pack for mod {}", entry.getKey(), t);
                }
                return null;
            }, this.executor));
        }

        int converted = 0;
        // Only the event's owning thread registers packs; worker completion order must not determine
        // pack priority, and the event API need not support concurrent mutations.
        for (var entry : results.entrySet()) {
            Path pack = entry.getValue().join();
            if (pack != null) {
                event.register(ResourcePack.create(PackCodec.path(pack)), PriorityOption.NORMAL);
                if (packsToLoad.containsKey(entry.getKey())) {
                    converted++;
                }
            }
        }

        LOGGER.info("Converted {} of {} packs for mods in {}", converted, packsToLoad.size(),
                FormatUtil.humanReadableFormat(System.currentTimeMillis() - start));
    }

    /**
     * Checks if the pack needs to be converted based on the generated UUID.
     * This allows pack regeneration if the mod file has changed.
     *
     * @param mod The mod to check.
     * @param packPath The path to the pack.
     * @return {@code true} if the pack needs to be converted.
     */
    private boolean checkNeedsConversion(ModInfo mod, Path packPath, long newestGeometry) {
        if (!Files.isRegularFile(packPath) || !PackCache.matches(packPath, PackUtil.getModUUID(mod.roots()))) {
            return true;
        }
        return isNewerThan(packPath, newestGeometry);
    }

    /**
     * Whether an entity model has appeared or changed since the pack was built.
     * <p>
     * A pack is otherwise only rebuilt when its mod changes, which misses the way entity models
     * arrive: they are converted by the Java client and land in a folder well after the pack was
     * last built. Without this the server keeps serving a pack made before the models existed, and
     * the mobs stay invisible no matter how many times it is restarted - with nothing in the log to
     * explain it, because the conversion that would have complained never ran.
     *
     * Scan once per load event instead of once for every cached mod pack.
     */
    private long newestEntityGeometry() {
        long newest = Long.MIN_VALUE;
        for (Path folder : List.of(
                this.hydraulic.dataFolder(Constants.MOD_ID).resolve("entity-geometry"),
                EntityGeometryDump.directory()
        )) {
            if (!Files.isDirectory(folder)) {
                continue;
            }

            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
                }
            } catch (IOException e) {
                LOGGER.warn("Cannot inspect entity geometry in {}; cached packs will be refreshed", folder, e);
                return Long.MAX_VALUE;
            }
        }

        return newest;
    }

    private static boolean isNewerThan(Path file, long newestGeometry) {
        try {
            return newestGeometry > Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return true;
        }
    }
}
