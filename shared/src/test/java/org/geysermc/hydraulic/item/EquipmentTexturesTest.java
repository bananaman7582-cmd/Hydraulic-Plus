package org.geysermc.hydraulic.item;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EquipmentTexturesTest {
    @Test
    void matchesMaterialLayersBeforeLooseItemNames() {
        Set<String> textures = Set.of("hazmat_suit_0", "hazmat_suit_1", "hazmat_boots");
        assertEquals("hazmat_suit_0",
                EquipmentTextures.matchLegacy(textures, "hazmat_suit", "hazmat_chestplate", false));
        assertEquals("hazmat_suit_1",
                EquipmentTextures.matchLegacy(textures, "hazmat_suit", "hazmat_leggings", true));
    }

    @Test
    void neverUsesBodyOnlyTextureForLeggings() {
        assertNull(EquipmentTextures.matchLegacy(Set.of("diving_suit"),
                "diving_suit", "diving_leggings", true));
    }
}
