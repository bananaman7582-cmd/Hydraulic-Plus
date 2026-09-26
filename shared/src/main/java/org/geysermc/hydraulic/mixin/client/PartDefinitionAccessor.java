package org.geysermc.hydraulic.mixin.client;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * A model part's own cubes and where it sits. Its children are already public; these are not.
 */
@Mixin(PartDefinition.class)
public interface PartDefinitionAccessor {
    @Accessor("cubes")
    List<CubeDefinition> hydraulic$cubes();

    @Accessor("partPose")
    PartPose hydraulic$partPose();
}
