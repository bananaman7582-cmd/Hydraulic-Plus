package org.geysermc.hydraulic.mixin.client;

import net.minecraft.client.model.geom.builders.MaterialDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The texture size a model's UVs are measured against, which Bedrock needs stated up front.
 */
@Mixin(MaterialDefinition.class)
public interface MaterialDefinitionAccessor {
    @Accessor("xTexSize")
    int hydraulic$textureWidth();

    @Accessor("yTexSize")
    int hydraulic$textureHeight();
}
