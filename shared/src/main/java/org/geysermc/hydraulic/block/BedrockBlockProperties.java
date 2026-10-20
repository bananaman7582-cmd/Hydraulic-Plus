package org.geysermc.hydraulic.block;

import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.List;

/** One property projection shared by block declarations, permutations and state mappings. */
final class BedrockBlockProperties {
    static final int MAX_VALUES = 16;

    private BedrockBlockProperties() {
    }

    static List<Property<?>> supported(Collection<Property<?>> properties) {
        return properties.stream().filter(property -> property.getPossibleValues().size() <= MAX_VALUES).toList();
    }
}
