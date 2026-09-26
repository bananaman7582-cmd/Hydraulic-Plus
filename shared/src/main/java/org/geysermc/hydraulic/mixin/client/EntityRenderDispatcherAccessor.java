package org.geysermc.hydraulic.mixin.client;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Every entity renderer the client has built, which is the only place a model that was never
 * registered as a layer can be found.
 */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherAccessor {
    @Accessor("renderers")
    Map<EntityType<?>, EntityRenderer<?, ?>> hydraulic$renderers();
}
