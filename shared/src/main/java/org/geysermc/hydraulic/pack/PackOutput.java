package org.geysermc.hydraulic.pack;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publishes completed packs without truncating the last usable pack on an export failure. */
final class PackOutput {
    private PackOutput() {
    }

    @FunctionalInterface
    interface Writer {
        void write(Path temporary) throws IOException;
    }

    static void write(Path destination, Writer writer) throws IOException {
        Path absolute = destination.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), "hydraulic-", ".tmp");
        try {
            writer.write(temporary);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
