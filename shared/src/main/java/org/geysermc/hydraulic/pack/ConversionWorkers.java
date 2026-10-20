package org.geysermc.hydraulic.pack;

/** Bounds simultaneous unpacked resource packs by both available processors and heap budget. */
final class ConversionWorkers {
    private static final long BYTES_PER_WORKER = 768L * 1024 * 1024;

    private ConversionWorkers() {
    }

    static int count(int processors, long maxHeap) {
        long memoryLimit = Math.max(1, maxHeap / 4 / BYTES_PER_WORKER);
        return (int) Math.max(1, Math.min(Math.min(4, processors / 2), memoryLimit));
    }
}
