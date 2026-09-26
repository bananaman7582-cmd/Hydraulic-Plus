package org.geysermc.hydraulic.pack;

import com.mojang.logging.LogUtils;
import org.geysermc.pack.converter.PackConverter;
import org.geysermc.pack.converter.PackageHandler;
import org.geysermc.pack.converter.util.LogListener;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// TODO Probably just do an empty pack check in PackConverter?
/**
 * Packs the pack into a zip file unless its empty.
 * <p>
 * This deliberately does <b>not</b> delegate to {@link PackageHandler#ZIP}: pack-converter's zip
 * util derives entry names from {@code File#getAbsolutePath()} without normalising the separator, so
 * on Windows every entry ends up as {@code textures\blocks\foo.png}. The ZIP spec requires forward
 * slashes, and Bedrock (consoles especially) fails to resolve the directory structure otherwise,
 * which shows up as missing textures/models/attachables.
 * <p>
 * It also shortens overly long paths, which some Bedrock platforms cannot load - see
 * {@link #MAX_PATH_LENGTH}.
 */
public class PackPackager implements PackageHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Some Bedrock platforms fail to load files whose path reaches this length. Mod assets hit it
     * easily, since converting prefixes the mod id: an armor texture such as
     * {@code assets/<mod>/textures/entity/equipment/humanoid_leggings/<name>.png} becomes
     * {@code textures/entity/<mod>/equipment/humanoid_leggings/<name>.png}.
     */
    private static final int MAX_PATH_LENGTH = 80;

    private static final int BUFFER_SIZE = 64 * 1024;

    @Override
    public void pack(@NotNull PackConverter converter, @NotNull Path path, @NotNull Path outputPath, @NotNull LogListener logger) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walker = Files.walk(path)) {
            walker.filter(Files::isRegularFile).forEach(files::add);
        }

        // Ignore empty packs (nothing but manifest.json / pack_icon.png)
        boolean hasContent = files.stream().anyMatch(filePath -> {
            String name = filePath.getFileName().toString();
            return !name.equals("manifest.json") && !name.equals("pack_icon.png");
        });
        if (!hasContent) {
            return;
        }

        Map<Path, String> entryNames = new LinkedHashMap<>();
        for (Path file : files) {
            entryNames.put(file, toEntryName(path.relativize(file)));
        }

        Map<String, String> renames = shortenLongPaths(entryNames.values());
        if (!renames.isEmpty()) {
            LOGGER.warn("Shortened {} path(s) that were too long for some Bedrock platforms", renames.size());
        }

        Files.createDirectories(outputPath.toAbsolutePath().getParent());

        long start = System.currentTimeMillis();

        // Buffer the output: a pack is thousands of small files, and writing an unbuffered stream
        // turns every one into its own set of syscalls. Several mods are converted at once, so that
        // contention adds up quickly - enough on a large modpack to keep the server thread waiting
        // past the watchdog's limit and have it kill the server.
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath), BUFFER_SIZE);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            // Packs are mostly PNG and OGG, which are already compressed, so squeezing them harder
            // costs a lot of time for almost no saving.
            zip.setLevel(Deflater.BEST_SPEED);

            for (Map.Entry<Path, String> entry : entryNames.entrySet()) {
                String name = entry.getValue();

                byte[] data;
                // Any file that references a renamed path has to be updated to match
                if (!renames.isEmpty() && name.endsWith(".json")) {
                    String content = Files.readString(entry.getKey(), StandardCharsets.UTF_8);
                    data = updateReferences(content, renames).getBytes(StandardCharsets.UTF_8);
                } else {
                    data = Files.readAllBytes(entry.getKey());
                }

                zip.putNextEntry(zipEntry(renames.getOrDefault(name, name), data));
                zip.write(data);
                zip.closeEntry();
            }
        }

        LOGGER.info("Packaged {} files in {}ms", entryNames.size(), System.currentTimeMillis() - start);
    }

    /**
     * Builds the entry for a file, storing it as-is when compressing it would be wasted work.
     * <p>
     * A pack is overwhelmingly PNG and OGG, both already compressed, so deflating them again costs
     * the setup and processing of a compressor per file to save almost nothing. With thousands of
     * small files and several packs converting at once that adds up to real time, and conversion
     * holds the server thread while it runs.
     *
     * @param name the entry name
     * @param data the file's contents
     * @return the entry to write
     */
    @NotNull
    private static ZipEntry zipEntry(@NotNull String name, byte[] data) {
        ZipEntry entry = new ZipEntry(name);

        if (!isAlreadyCompressed(name)) {
            entry.setMethod(ZipEntry.DEFLATED);
            return entry;
        }

        // Stored entries have to carry their own size and checksum
        CRC32 checksum = new CRC32();
        checksum.update(data);

        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(checksum.getValue());

        return entry;
    }

    private static boolean isAlreadyCompressed(@NotNull String name) {
        return name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".fsb")
                || name.endsWith(".tga") || name.endsWith(".jpg");
    }

    /**
     * Picks a shorter path for every entry that is too long for Bedrock.
     *
     * @param entries the entry names in the pack
     * @return a map of the original entry name to its replacement, for those that needed shortening
     */
    @NotNull
    private static Map<String, String> shortenLongPaths(@NotNull Iterable<String> entries) {
        Map<String, String> renames = new HashMap<>();

        Set<String> taken = new HashSet<>();
        entries.forEach(taken::add);

        for (String entry : entries) {
            if (entry.length() < MAX_PATH_LENGTH) {
                continue;
            }

            // Keep the top level directory (Bedrock resolves files by it) and the file name, and
            // collapse everything between them into a hash of the original path so it stays
            // deterministic across conversions and unique per file.
            int firstSlash = entry.indexOf('/');
            int lastSlash = entry.lastIndexOf('/');

            String prefix = firstSlash < 0 ? "" : entry.substring(0, firstSlash + 1);
            String name = entry.substring(lastSlash + 1);
            String hash = Integer.toHexString(entry.hashCode());

            // A file sitting directly in its top level directory has nothing in the middle to
            // collapse - its own name is the whole length. Hydraulic writes such names itself:
            // animation_controllers/<mod>.<entity>.animation_controllers.json passes the limit once
            // the mod and entity names together reach about thirty characters, which is ordinary
            String shortened = null;
            for (int attempt = 0; shortened == null; attempt++) {
                String salt = attempt == 0 ? hash : hash + "_" + attempt;
                String directory = firstSlash == lastSlash ? "" : salt + "/";

                String candidate = prefix + directory + name;
                if (candidate.length() >= MAX_PATH_LENGTH) {
                    // Collapsing directories was not enough, or there were none to collapse
                    candidate = prefix + directory + shortenName(name, salt,
                            MAX_PATH_LENGTH - 1 - prefix.length() - directory.length());
                }

                if (!taken.contains(candidate)) {
                    shortened = candidate;
                }
            }

            if (shortened.length() >= entry.length()) {
                continue; // no point renaming if it doesn't actually help
            }

            taken.add(shortened);
            renames.put(entry, shortened);
        }

        return renames;
    }

    /**
     * Cuts a file's own name down to fit, keeping what identifies it at both ends.
     * <p>
     * The extension is kept because Bedrock finds files by it - a controller that stops ending in
     * {@code .json} is simply not read - and the hash is kept so that two files whose names begin
     * alike cannot collapse onto each other. Whatever room is left goes to the front of the name,
     * which is where the mod and the thing it belongs to are written.
     *
     * @param name the file name to shorten
     * @param hash what makes the result unique
     * @param room how many characters the name may take up
     * @return the shortened name
     */
    @NotNull
    private static String shortenName(@NotNull String name, @NotNull String hash, int room) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";

        // The hash and the dot joining it to the name have to fit as well
        int keep = Math.min(stem.length(), room - hash.length() - 1 - extension.length());
        if (keep < 1) {
            // No room even for that, so the name becomes the hash alone. Only a pack nested far
            // deeper than Bedrock's own layout can reach this
            return hash + extension;
        }

        return stem.substring(0, keep) + "." + hash + extension;
    }

    /**
     * Rewrites references to renamed files. Bedrock refers to textures without their extension, so
     * both forms are replaced. Longest paths are replaced first so that a path which is a prefix of
     * another can't corrupt it.
     *
     * @param content the file content to update
     * @param renames the renames to apply
     * @return the updated content
     */
    @NotNull
    private static String updateReferences(@NotNull String content, @NotNull Map<String, String> renames) {
        List<Map.Entry<String, String>> ordered = new ArrayList<>(renames.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed());

        for (Map.Entry<String, String> rename : ordered) {
            content = content.replace(rename.getKey(), rename.getValue());
            content = content.replace(withoutExtension(rename.getKey()), withoutExtension(rename.getValue()));
        }

        return content;
    }

    @NotNull
    private static String withoutExtension(@NotNull String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash ? path.substring(0, dot) : path;
    }

    /**
     * Joins the path components with {@code '/'} regardless of the platform separator.
     */
    @NotNull
    private static String toEntryName(@NotNull Path relative) {
        StringBuilder builder = new StringBuilder();
        for (Path part : relative) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(part);
        }
        return builder.toString();
    }
}
