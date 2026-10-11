package org.geysermc.hydraulic.pack.modules;

import com.google.auto.service.AutoService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.util.ResourceCompatibility;
import org.geysermc.pack.bedrock.resource.Languages;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/** Supplies readable English names only when a mod omitted its own translation. */
@AutoService(PackModule.class)
public final class FallbackTranslationPackModule extends PackModule<FallbackTranslationPackModule> {
    public FallbackTranslationPackModule() {
        this.postProcess(this::postProcess);
    }

    private void postProcess(@NotNull PackPostProcessContext<FallbackTranslationPackModule> context) {
        Languages languages = context.bedrockResourcePack().languages();
        Map<String, String> english = languages == null || languages.language("en_US") == null
                ? new HashMap<>()
                : new HashMap<>(languages.language("en_US"));

        int added = 0;
        for (Item item : context.registryValues(BuiltInRegistries.ITEM)) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null && !english.containsKey(item.getDescriptionId())) {
                english.put(item.getDescriptionId(), ResourceCompatibility.readableName(id.getPath()));
                added++;
            }
        }
        for (Block block : context.registryValues(BuiltInRegistries.BLOCK)) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id != null && !english.containsKey(block.getDescriptionId())) {
                english.put(block.getDescriptionId(), ResourceCompatibility.readableName(id.getPath()));
                added++;
            }
        }

        if (added > 0) {
            context.bedrockResourcePack().addLanguage("en_US", english);
            context.logger().info("Generated {} missing English item/block name(s)", added);
        }
    }
}
