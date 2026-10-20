package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversionWorkersTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void boundsMemoryAndProcessorPressure() {
        assertEquals(1, ConversionWorkers.count(1, 16 * GIB));
        assertEquals(1, ConversionWorkers.count(12, 2 * GIB));
        assertEquals(1, ConversionWorkers.count(12, 4 * GIB));
        assertEquals(3, ConversionWorkers.count(12, 10 * GIB));
        assertEquals(4, ConversionWorkers.count(64, 32 * GIB));
    }
}
