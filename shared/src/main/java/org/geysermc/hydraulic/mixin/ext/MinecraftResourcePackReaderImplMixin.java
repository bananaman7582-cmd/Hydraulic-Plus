package org.geysermc.hydraulic.mixin.ext;

import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import net.kyori.adventure.key.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.geysermc.hydraulic.util.ResourceCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import team.unnamed.creative.metadata.Metadata;
import team.unnamed.creative.metadata.pack.PackFormat;
import team.unnamed.creative.overlay.ResourceContainer;
import team.unnamed.creative.part.ResourcePackPart;
import team.unnamed.creative.serialize.minecraft.GsonUtil;
import team.unnamed.creative.serialize.minecraft.io.JsonResourceDeserializer;
import team.unnamed.creative.serialize.minecraft.metadata.MetadataSerializer;

import java.io.IOException;
import java.util.Locale;

@Mixin(targets = "team.unnamed.creative.serialize.minecraft.MinecraftResourcePackReaderImpl", remap = false)
public abstract class MinecraftResourcePackReaderImplMixin {
    private static Logger LOGGER = LoggerFactory.getLogger("MinecraftResourcePackReaderImplMixin");

    /**
     * Redirect the parseJson method to catch any exceptions that may occur
     * This means a single bad json file won't cause the entire resource pack to fail loading
     */
    @Redirect(
        method = "parseJson",
        at = @At(
            value = "INVOKE",
            target = "Lteam/unnamed/creative/serialize/minecraft/GsonUtil;parseReader(Lcom/google/gson/stream/JsonReader;)Lcom/google/gson/JsonElement;"
        )
    )
    private JsonElement parseJson(JsonReader reader) {
        try {
            return GsonUtil.parseReader(reader);
        } catch (Exception e) {
            LOGGER.error("Failed to parse JSON: " + e.getMessage());
        }

        return null;
    }

    /**
     * Redirect the deserializeFromJson to ignore any null JsonElements
     * Also catch any exceptions that may occur and log them
     */
    @Redirect(
        method = "read(Lteam/unnamed/creative/serialize/minecraft/fs/FileTreeReader;)Lteam/unnamed/creative/ResourcePack;",
        at = @At(
            value = "INVOKE",
            target = "Lteam/unnamed/creative/serialize/minecraft/io/JsonResourceDeserializer;deserializeFromJson(Lcom/google/gson/JsonElement;Lnet/kyori/adventure/key/Key;Lteam/unnamed/creative/metadata/pack/PackFormat;)Ljava/lang/Object;"
        )
    )
    private Object deserializeFromJson(JsonResourceDeserializer instance, JsonElement jsonElement, Key key, PackFormat packFormat) throws IOException {
        if (jsonElement == null) {
            return null;
        }

        try {
            return instance.deserializeFromJson(jsonElement, key, packFormat);
        } catch (Exception e) {
            // A mod naming something of its own is the usual reason, and the file is normally still
            // mostly readable - so try again without the part that cannot be understood before
            // giving up on it entirely
            JsonElement simplified = jsonElement.deepCopy();
            if (ResourceCompatibility.simplifyModdedRendering(simplified)) {
                try {
                    Object recovered = instance.deserializeFromJson(simplified, key, packFormat);
                    LOGGER.debug("Read {} without the parts only its own mod can draw", key);
                    return recovered;
                } catch (Exception ignored) {
                    // Not the part that was in the way; report the original failure below
                }
            }

            LOGGER.error("Failed to deserialize JSON (" + key + "): " + e.getMessage());
        }

        return null;
    }

    @Redirect(
            method = "read(Lteam/unnamed/creative/serialize/minecraft/fs/FileTreeReader;)Lteam/unnamed/creative/ResourcePack;",
            at = @At(
                    value = "INVOKE",
                    target = "Lteam/unnamed/creative/part/ResourcePackPart;addTo(Lteam/unnamed/creative/overlay/ResourceContainer;)V"
            )
    )
    private void addTo(ResourcePackPart instance, ResourceContainer resourceContainer) {
        if (instance != null) {
            instance.addTo(resourceContainer);
        }
    }

    //Key key = Key.key(namespace, keyValue);
    // Applies to every Key.key(namespace, value) call in read(...), not just the resource-category one:
    // some mods (e.g. Distant Horizons) ship files like "jar/themeDark.svg" whose path has uppercase
    // characters, which Adventure rejects with InvalidKeyException and aborts the whole pack read.
    @ModifyArgs(
            method = "read(Lteam/unnamed/creative/serialize/minecraft/fs/FileTreeReader;)Lteam/unnamed/creative/ResourcePack;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/kyori/adventure/key/Key;key(Ljava/lang/String;Ljava/lang/String;)Lnet/kyori/adventure/key/Key;"
            )
    )
    private void injectKeyCreation(Args args) {
        if (args.get(1) instanceof String value) {
            args.set(1, value.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Redirect the pack metadata read so a malformed pack.mcmeta (e.g. missing pack_format, as shipped by
     * Forge Config API Port) logs and yields empty metadata instead of throwing and aborting startup.
     */
    @Redirect(
            method = "read(Lteam/unnamed/creative/serialize/minecraft/fs/FileTreeReader;)Lteam/unnamed/creative/ResourcePack;",
            at = @At(
                    value = "INVOKE",
                    target = "Lteam/unnamed/creative/serialize/minecraft/metadata/MetadataSerializer;readFromTree(Lcom/google/gson/JsonElement;)Lteam/unnamed/creative/metadata/Metadata;"
            )
    )
    private Metadata readMetadataFromTree(MetadataSerializer instance, JsonElement element) {
        try {
            return instance.readFromTree(element);
        } catch (Exception e) {
            LOGGER.error("Failed to read pack metadata: " + e.getMessage());
            return Metadata.empty();
        }
    }
}
