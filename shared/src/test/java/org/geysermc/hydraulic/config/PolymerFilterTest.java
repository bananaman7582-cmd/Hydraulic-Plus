package org.geysermc.hydraulic.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymerFilterTest {
    @Test
    void keepsProtocolBridgeChannelsAndDropsJavaModChannels() {
        assertFalse(PolymerFilter.isUnreadableNamespace("minecraft"));
        assertFalse(PolymerFilter.isUnreadableNamespace("geyser"));
        assertFalse(PolymerFilter.isUnreadableNamespace("floodgate"));
        assertTrue(PolymerFilter.isUnreadableNamespace("enderscape"));
        assertTrue(PolymerFilter.isUnreadableNamespace("liminalpools"));
    }
}
