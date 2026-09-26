package org.geysermc.hydraulic.mixin.client;

import net.minecraft.client.model.geom.builders.CubeDeformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * How far a cube is grown past its stated size - Bedrock calls the same thing inflate.
 */
@Mixin(CubeDeformation.class)
public interface CubeDeformationAccessor {
    @Accessor("growX")
    float hydraulic$growX();

    @Accessor("growY")
    float hydraulic$growY();

    @Accessor("growZ")
    float hydraulic$growZ();
}
