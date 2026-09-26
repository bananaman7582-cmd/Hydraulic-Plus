package org.geysermc.hydraulic.mixin.server;

import net.minecraft.server.MinecraftServer;
import org.geysermc.hydraulic.compat.alexsmobs.MurmurNeck;
import org.geysermc.hydraulic.entity.ActionAnimations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Gives {@link ActionAnimations} a moment each tick to notice what mobs have started doing.
 * <p>
 * The animations a mob performs deliberately - biting, eating - are begun by the server rather than
 * worked out by whoever is watching, so there has to be somewhere that sees them begin. This is the
 * end of the tick, once mobs have decided what they are doing but before the next one starts.
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void hydraulic$playStartedAnimations(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ActionAnimations.tick((MinecraftServer) (Object) this);
        MurmurNeck.tick((MinecraftServer) (Object) this);
    }
}
