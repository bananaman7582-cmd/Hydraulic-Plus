package org.geysermc.hydraulic.mixin.client;

import net.minecraft.client.Minecraft;
import org.geysermc.hydraulic.entity.client.EntitySampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Waits until the player is standing in a world, which is the first moment a mob can be built to ask
 * questions of - and the first moment there is anyone to tell about it.
 * <p>
 * Reading models out of the renderers happens as resources load, long before any world exists. That
 * is early enough for the shape of a mob but not for its texture or its animation, both of which are
 * decided per creature and so need a creature to exist.
 * <p>
 * The world arriving is not quite enough on its own: it is set up a moment before the player is, so
 * hooking that instead would leave nobody in the world to be told the reading had finished.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void hydraulic$sampleEntities(CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        if (client.level != null && client.player != null) {
            EntitySampler.sample(client.level);
        }
    }
}
