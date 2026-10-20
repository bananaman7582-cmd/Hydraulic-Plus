package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PackOutputTest {
    @TempDir Path directory;

    @Test
    void failedExportKeepsOldPackAndCleansTemporaryFile() throws IOException {
        Path pack = directory.resolve("test.mcpack");
        Files.writeString(pack, "old pack");
        assertThrows(IOException.class, () -> PackOutput.write(pack, temporary -> {
            Files.writeString(temporary, "partial export");
            throw new IOException("simulated exporter failure");
        }));
        assertEquals("old pack", Files.readString(pack));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void completeExportReplacesOldPack() throws IOException {
        Path pack = directory.resolve("nested/test.mcpack");
        PackOutput.write(pack, temporary -> Files.writeString(temporary, "new pack"));
        assertEquals("new pack", Files.readString(pack));
        PackOutput.write(pack, temporary -> Files.writeString(temporary, "replacement"));
        assertEquals("replacement", Files.readString(pack));
    }
}
