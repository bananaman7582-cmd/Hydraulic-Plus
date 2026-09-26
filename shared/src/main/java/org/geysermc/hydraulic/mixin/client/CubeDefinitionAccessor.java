package org.geysermc.hydraulic.mixin.client;

import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.UVPair;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Everything describing one box of an entity model, all of which Bedrock has a direct equivalent for.
 */
@Mixin(CubeDefinition.class)
public interface CubeDefinitionAccessor {
    @Accessor("origin")
    Vector3fc hydraulic$origin();

    @Accessor("dimensions")
    Vector3fc hydraulic$dimensions();

    @Accessor("grow")
    CubeDeformation hydraulic$grow();

    @Accessor("mirror")
    boolean hydraulic$mirror();

    @Accessor("texCoord")
    UVPair hydraulic$texCoord();
}
