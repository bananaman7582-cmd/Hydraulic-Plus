package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackCacheTest {
    @TempDir Path directory;

    @Test
    void malformedCachesAreMissesInsteadOfStartupFailures() throws IOException {
        UUID expected = UUID.randomUUID();
        Path pack = directory.resolve("test.mcpack");
        assertFalse(PackCache.matches(pack, expected));
        Files.writeString(pack, "truncated zip");
        assertFalse(PackCache.matches(pack, expected));
        for (String json : new String[] {"null", "[]", "{", "{}", "{\"header\":{}}", "{\"header\":null}"}) {
            writePack(pack, "manifest.json", json);
            assertFalse(PackCache.matches(pack, expected), json);
        }
        writePack(pack, "other.json", "{}");
        assertFalse(PackCache.matches(pack, expected));
    }

    @Test
    void onlyMatchingSourceFingerprintIsReused() throws IOException {
        UUID expected = UUID.randomUUID();
        Path pack = directory.resolve("test.mcpack");
        writePack(pack, "manifest.json", "{\"header\":{\"uuid\":\"" + expected + "\"}}");
        assertTrue(PackCache.matches(pack, expected));
        assertFalse(PackCache.matches(pack, UUID.randomUUID()));
    }

    private static void writePack(Path path, String name, String text) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
