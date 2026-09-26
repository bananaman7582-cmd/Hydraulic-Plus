package org.geysermc.hydraulic.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.geysermc.geyser.text.MinecraftLocale;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Makes the translations shipped by mods available to Geyser.
 * <p>
 * Geyser renders several things server-side rather than letting the Bedrock client translate them -
 * most visibly the advancements menu, which resolves titles and descriptions through
 * {@link MinecraftLocale}. That only contains vanilla Java translations, and unknown keys are
 * returned verbatim, so modded entries show up as raw keys such as
 * {@code advancements.somemod.nether.some_armor.title}. Copying each mod's language files into
 * Geyser's locale store fixes those without affecting the Bedrock pack's own translations.
 */
public final class TranslationUtil {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TranslationUtil() {
    }

    /**
     * Injects mod translations into every locale Geyser has already loaded.
     */
    public static void injectLoadedLocales() {
        for (String locale : List.copyOf(MinecraftLocale.LOCALE_MAPPINGS.keySet())) {
            inject(locale);
        }
    }

    /**
     * Injects the translations the loaded mods provide for the given locale into Geyser's locale
     * store. Vanilla entries take priority, so a mod can't accidentally clobber vanilla strings.
     *
     * @param locale the locale to inject, e.g. {@code en_us}
     */
    public static void inject(@NotNull String locale) {
        Map<String, String> vanilla = MinecraftLocale.LOCALE_MAPPINGS.get(locale);
        if (vanilla == null) {
            return; // Geyser hasn't loaded this locale, nothing to merge into
        }

        Map<String, String> translations = modTranslations(locale);
        if (translations.isEmpty()) {
            return;
        }

        // The map Geyser stores may be immutable, so merge into a copy and replace it
        Map<String, String> merged = new HashMap<>(vanilla);
        int before = merged.size();
        translations.forEach(merged::putIfAbsent);
        if (merged.size() == before) {
            return; // everything was already present
        }

        MinecraftLocale.LOCALE_MAPPINGS.put(locale, merged);
        LOGGER.info("Added {} mod translations to Geyser locale {}", merged.size() - before, locale);
    }

    /**
     * Reads every {@code assets/<namespace>/lang/<locale>.json} file provided by the loaded mods.
     *
     * @param locale the locale to read, e.g. {@code en_us}
     * @return the translations the mods provide for that locale
     */
    @NotNull
    public static Map<String, String> modTranslations(@NotNull String locale) {
        String fileName = locale.toLowerCase(Locale.ROOT) + ".json";

        Map<String, String> translations = new HashMap<>();
        for (ModInfo mod : HydraulicImpl.instance().mods()) {
            for (Path root : mod.roots()) {
                Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) {
                    continue;
                }

                try (Stream<Path> namespaces = Files.list(assets)) {
                    namespaces.filter(Files::isDirectory)
                            .map(namespace -> namespace.resolve("lang").resolve(fileName))
                            .filter(Files::isRegularFile)
                            .forEach(file -> read(file, translations));
                } catch (Exception e) {
                    LOGGER.warn("Failed to list namespaces of mod {} for translations", mod.id(), e);
                }
            }
        }

        return translations;
    }

    private static void read(@NotNull Path file, @NotNull Map<String, String> output) {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!(parsed instanceof JsonObject object)) {
                return;
            }

            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive()) {
                    output.put(entry.getKey(), value.getAsString());
                }
            }
        } catch (Exception e) {
            // A single malformed language file shouldn't stop the rest from loading
            LOGGER.warn("Failed to read language file {}: {}", file, e.getMessage());
        }
    }
}
