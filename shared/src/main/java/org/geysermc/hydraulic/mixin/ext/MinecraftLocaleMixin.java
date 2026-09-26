package org.geysermc.hydraulic.mixin.ext;

import org.geysermc.geyser.text.MinecraftLocale;
import org.geysermc.hydraulic.util.TranslationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds mod translations to each locale Geyser loads.
 * <p>
 * Geyser loads locales lazily - en_US on startup, and others as players with that locale connect -
 * replacing whatever is stored for that locale. Merging our entries in right after it loads means
 * they survive, so server-side translated text (most visibly the advancements menu) resolves modded
 * keys instead of showing them raw.
 */
@Mixin(value = MinecraftLocale.class, remap = false)
public class MinecraftLocaleMixin {

    @Inject(method = "loadLocale", at = @At("RETURN"))
    private static void addModTranslations(String locale, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return; // Geyser failed to load this locale, there is nothing to merge into
        }

        try {
            TranslationUtil.inject(locale);
        } catch (Throwable t) {
            // Never let translation injection break locale loading
            org.slf4j.LoggerFactory.getLogger("Hydraulic").warn("Failed to add mod translations to locale {}", locale, t);
        }
    }
}
