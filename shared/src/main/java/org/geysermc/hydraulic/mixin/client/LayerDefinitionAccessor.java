package org.geysermc.hydraulic.mixin.client;

import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MaterialDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the parts of a baked entity model layer that Minecraft keeps to itself.
 * <p>
 * A layer holds everything needed to describe an entity's shape, but exposes only enough to bake it
 * for rendering. Reading the shape out instead - to hand it to Bedrock - means getting at the mesh
 * and the texture size behind it.
 */
@Mixin(LayerDefinition.class)
public interface LayerDefinitionAccessor {
    @Accessor("mesh")
    MeshDefinition hydraulic$mesh();

    @Accessor("material")
    MaterialDefinition hydraulic$material();
}
