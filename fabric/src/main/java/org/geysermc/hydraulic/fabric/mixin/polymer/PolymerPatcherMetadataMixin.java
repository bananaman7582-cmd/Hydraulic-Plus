package org.geysermc.hydraulic.fabric.mixin.polymer;

import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.geysermc.hydraulic.fabric.polymer.BedrockAudience;
import org.geysermc.hydraulic.util.BedrockPlayers;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Hydraulic has already normalised these indices; do not shift them a second time at encoding. */
@Pseudo
@Mixin(targets = "me.drex.polymerpatcher.util.VanillaEntityData", remap = false)
public class PolymerPatcherMetadataMixin {
    @Inject(method = "sanitize(Ljava/util/List;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void hydraulic$keepBedrockMetadata(List<SynchedEntityData.DataValue<?>> values,
                                                       @Nullable Entity entity, @Nullable ServerPlayer player,
                                                       CallbackInfoReturnable<List<SynchedEntityData.DataValue<?>>> cir) {
        if ((player != null && BedrockPlayers.isBedrock(player.getUUID()))
                || BedrockAudience.isBedrock(PacketContext.get())) {
            cir.setReturnValue(values);
        }
    }
}
