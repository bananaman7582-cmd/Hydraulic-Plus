package org.geysermc.hydraulic.fabric.polymer;

import eu.pb4.polymer.core.api.block.BlockMapper;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block mapper that changes nothing, handed to Bedrock players in place of Polymer's.
 * <p>
 * Polymer routes every block it disguises through whichever mapper belongs to the player being sent
 * to, so replacing the mapper covers every path at once - not only the one call this started with.
 * A mod that asks Polymer to map a state itself, rather than going through the usual entry point,
 * gets the same answer, because the answer comes from the mapper rather than from the caller.
 */
public final class RealBlocks implements BlockMapper {
    public static final RealBlocks INSTANCE = new RealBlocks();

    private RealBlocks() {
    }

    @Override
    public BlockState toClientSideState(BlockState state, PacketContext context) {
        return state;
    }

    @Override
    public String getMapperName() {
        return "hydraulic:bedrock";
    }
}
