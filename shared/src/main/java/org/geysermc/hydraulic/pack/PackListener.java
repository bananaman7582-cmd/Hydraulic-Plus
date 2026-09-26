package org.geysermc.hydraulic.pack;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
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
import org.geysermc.pack.bedrock.resource.Manifest;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

/**
 * Listens for events related to packs.
 */
public class PackListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final ExecutorService THREAD_POOL;

    private final HydraulicImpl hydraulic;
    private final PackManager manager;

    static {
        // Nearly every core, because there is nothing to share them with. The server thread spends
        // this entire stretch blocked in join() below waiting for these very tasks, so holding cores
        // back for it protects a thread that is doing nothing - it only leaves the machine idle while
        // the watchdog counts towards the sixty seconds at which it declares the server dead. One is
        // left over for the rest of the system rather than for the server.
        //
        // This helps in proportion to how many mods there are, and does nothing for a pack whose one
        // enormous mod is the long pole. A hundred and fifteen packs is the first case.
        THREAD_POOL = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() - 1),
            new ThreadFactoryBuilder()
                .setNameFormat(Constants.MOD_NAME + " Conversion Thread #%d")
                .setUncaughtExceptionHandler((thread, throwable) -> LOGGER.error("Uncaught exception in thread {}", thread.getName(), throwable))
                .build()
        );
    }

    public PackListener(HydraulicImpl hydraulic, PackManager manager) {
        this.hydraulic = hydraulic;
        this.manager = manager;

        hydraulic.registerServerStop(server -> {
            THREAD_POOL.shutdown(); // Prevents the server from locking up on stop
        });
    }

    @Subscribe(postOrder = PostOrder.LATE)
    public void onLoadResourcePacks(GeyserDefineResourcePacksEvent event) {
        // Check if hydraulic has updated since the last pack conversion
        // This is so we can regenerate packs on update in case the pack generation logic has changed
        ModInfo hydraulicMod = this.hydraulic.mod(Constants.MOD_ID);
        boolean hydraulicUpdated = checkNeedsConversion(hydraulicMod, this.hydraulic.modStorage(hydraulicMod).pack());

        if (hydraulicUpdated) {
            LOGGER.info("Hydraulic has updated since the last pack conversion, regenerating all packs!");
        }

        // Go over all mods and load the pack or mark them for conversion
        Map<String, Pair<ModInfo, Path>> packsToLoad = new HashMap<>();
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
            if (this.hydraulic.isDev() || hydraulicUpdated || checkNeedsConversion(mod, packPath)) {
                packsToLoad.put(mod.id(), Pair.of(mod, packPath));
            } else {
                // We don't need to convert the pack, just register it
                LOGGER.info("Registering already converted pack for mod {}", mod.id());
                event.register(ResourcePack.create(PackCodec.path(packPath)), PriorityOption.NORMAL);
            }
        }

        if (packsToLoad.isEmpty()) {
            return;
        }

        LOGGER.info("Found {} packs to convert!", packsToLoad.size());

        long start = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var entry : packsToLoad.entrySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                LOGGER.info("Converting pack for mod {}", entry.getKey());
                try {
                    if (this.manager.createPack(entry.getValue().getLeft(), entry.getValue().getRight())) {
                        event.register(ResourcePack.create(PackCodec.path(entry.getValue().getRight())), PriorityOption.NORMAL);
                    }
                } catch (Throwable t) {
                    LOGGER.error("Failed to convert pack for mod {}", entry.getKey(), t);
                }
            }, THREAD_POOL));
        }

        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        LOGGER.info("Converted {} packs for mods in {}", packsToLoad.size(), FormatUtil.humanReadableFormat(System.currentTimeMillis() - start));
    }

    /**
     * Checks if the pack needs to be converted based on the generated UUID.
     * This allows pack regeneration if the mod file has changed.
     *
     * @param mod The mod to check.
     * @param packPath The path to the pack.
     * @return {@code true} if the pack needs to be converted.
     */
    private boolean checkNeedsConversion(ModInfo mod, Path packPath) {
        // Read the uuid from the pack manifest
        String packUUID;
        try (
            ZipFile zip = new ZipFile(packPath.toFile());
            InputStream inputStream = zip.getInputStream(zip.getEntry("manifest.json"));
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream)
        ) {
            packUUID = GSON.fromJson(inputStreamReader, Manifest.class).header().uuid();
        } catch (IOException e) {
            return true;
        }

        String modUUID = PackUtil.getModUUID(mod.roots()).toString();
        if (!modUUID.equals(packUUID)) {
            return true;
        }

        return hasNewerEntityGeometry(packPath);
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
     * @param packPath the pack being considered
     * @return whether any model is newer than the pack
     */
    private boolean hasNewerEntityGeometry(Path packPath) {
        long packTime;
        try {
            packTime = Files.getLastModifiedTime(packPath).toMillis();
        } catch (IOException e) {
            return true;
        }

        for (Path folder : List.of(
                this.hydraulic.dataFolder(Constants.MOD_ID).resolve("entity-geometry"),
                EntityGeometryDump.directory()
        )) {
            if (!Files.isDirectory(folder)) {
                continue;
            }

            try (Stream<Path> files = Files.list(folder)) {
                if (files.anyMatch(file -> isNewerThan(file, packTime))) {
                    return true;
                }
            } catch (IOException e) {
                // A folder we cannot read tells us nothing, so leave the pack alone
            }
        }

        return false;
    }

    private static boolean isNewerThan(Path file, long time) {
        try {
            return Files.getLastModifiedTime(file).toMillis() > time;
        } catch (IOException e) {
            return false;
        }
    }
}
