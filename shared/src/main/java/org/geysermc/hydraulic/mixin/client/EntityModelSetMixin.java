package org.geysermc.hydraulic.mixin.client;

import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.geysermc.hydraulic.entity.client.EntityGeometryDump;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Catches every entity model the client loads, so the shapes can be handed to Bedrock.
 * <p>
 * This is the one moment the models are all in one place and fully assembled - mods have registered
 * theirs by now, and nothing has been baked into triangles yet. The set is rebuilt whenever
 * resources reload, so a mod added later is picked up without anything else having to notice.
 */
@Mixin(EntityModelSet.class)
public class EntityModelSetMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void hydraulic$dumpModdedModels(Map<ModelLayerLocation, LayerDefinition> roots, CallbackInfo ci) {
        EntityGeometryDump.write(roots);
    }
}
