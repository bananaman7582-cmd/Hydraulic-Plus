package org.geysermc.hydraulic.mixin.client;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EntityType;
import org.geysermc.hydraulic.entity.client.EntityGeometryDump;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Catches entity models that were never registered as model layers.
 * <p>
 * {@link EntityModelSetMixin} finds every model the game itself keeps track of, which is most of
 * them. It does not find the models a mod builds for itself - Alex's Mobs and the rest of Citadel's
 * users construct theirs inside their renderers, so they appear in no registry and that hook sees an
 * empty world.
 * <p>
 * Renderers, though, are all in one place once resources have loaded, and each living one holds the
 * model it draws with. Reading them here reaches the models the other hook cannot, and does it after
 * a reload so a mod added later is picked up the same way.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void hydraulic$dumpRendererModels(ResourceManager resources, CallbackInfo ci) {
        Map<EntityType<?>, Object> models = new HashMap<>();
        Map<EntityType<?>, String> textures = new HashMap<>();

        for (Map.Entry<EntityType<?>, EntityRenderer<?, ?>> entry
                : ((EntityRenderDispatcherAccessor) this).hydraulic$renderers().entrySet()) {
            if (!(entry.getValue() instanceof LivingEntityRenderer<?, ?, ?> living)) {
                continue;
            }

            Object model = living.getModel();
            if (model != null) {
                models.put(entry.getKey(), model);
            }

            // Which texture a mob wears is the renderer's to decide, and guessing it from the mob's
            // name goes wrong the moment a mod does not match the two up - a centipede_head drawn
            // with cave_centipede.png, a catfish that only has catfish_large. Asking outright is
            // exact where guessing cannot be
            hydraulic$textureOf(living).ifPresent(texture -> textures.put(entry.getKey(), texture));
        }

        EntityGeometryDump.writeRendererModels(models, textures);
    }

    /**
     * Asks a renderer which texture it draws with.
     * <p>
     * It is asked without a mob to describe, which most renderers do not mind - the answer is a
     * constant for all but the ones that pick a texture per creature. Those throw instead, and are
     * left to be guessed at from the name as before.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Optional<String> hydraulic$textureOf(LivingEntityRenderer<?, ?, ?> renderer) {
        try {
            Identifier texture = ((LivingEntityRenderer) renderer).getTextureLocation(null);
            return texture == null ? Optional.empty() : Optional.of(texture.toString());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
