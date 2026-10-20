package org.geysermc.hydraulic.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayersTest {
    @Test
    void linkedAccountUsesGeyserRatherThanUuidShape() {
        UUID linked = UUID.fromString("5fa20eca-52e3-4a5b-8091-c7fb19dfe881");
        assertTrue(BedrockPlayers.isBedrock(linked, linked::equals));
        assertFalse(BedrockPlayers.isBedrock(linked, id -> false));
    }

    @Test
    void floodgateWorksBeforeSessionAndMissingAudienceIsNotBedrock() {
        assertTrue(BedrockPlayers.isBedrock(new UUID(0, 100), id -> fail("Session lookup was unnecessary")));
        assertFalse(BedrockPlayers.isBedrock(null, id -> fail("No audience")));
    }
}
