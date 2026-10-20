package org.geysermc.hydraulic.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipFile;

/** Treats missing, truncated and malformed cached manifests as cache misses. */
final class PackCache {
    private PackCache() {
    }

    static boolean matches(Path pack, UUID expected) {
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry("manifest.json");
            if (entry == null) {
                return false;
            }
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                if (!(JsonParser.parseReader(reader) instanceof JsonObject root)
                        || !(root.get("header") instanceof JsonObject header)
                        || !(header.get("uuid") instanceof JsonPrimitive uuid) || !uuid.isString()) {
                    return false;
                }
                return expected.toString().equals(uuid.getAsString());
            }
        } catch (IOException | JsonParseException e) {
            return false;
        }
    }
}
