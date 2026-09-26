package org.geysermc.hydraulic.entity.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.geysermc.hydraulic.mixin.client.EntityRenderDispatcherAccessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds the vanilla creature a mod's own is drawn as, for mods that vary one rather than invent one.
 * <p>
 * A frozen spider is a spider wearing a different texture. Its renderer hands back the game's own
 * {@code SpiderModel}, and nothing about that model was registered by the mod - it reuses the layer
 * the game already loaded. So there is no modded layer to convert, no Citadel model to read, and the
 * mob fell through every path here to arrive on Bedrock as whatever the category guess produced.
 * <p>
 * What it does have is the model, and the game has exactly one vanilla creature drawn with each
 * model class. Matching on that says which vanilla shape to convert, and the mod's own texture is
 * already recorded from its renderer - so the mob comes out the right shape wearing the right skin.
 */
public final class VanillaShapes {
    private static final String VANILLA = "minecraft";

    /**
     * The layer every creature's main body is built from. Hats, armour and the rest are separate
     * layers and are not what a mob is shaped like.
     */
    private static final String MAIN = "main";

    private static Map<Class<?>, Identifier> byModel;

    private VanillaShapes() {
    }

    /**
     * The vanilla layer a modded mob's model was built from, if the model is one of the game's own.
     *
     * @param model the model its renderer draws with
     * @param layers every layer the client has loaded
     * @return the matching vanilla layer definition, or null if this is not a vanilla model
     */
    @Nullable
    public static LayerDefinition shapeOf(@NotNull Object model,
                                          @NotNull Map<ModelLayerLocation, LayerDefinition> layers) {
        Identifier vanilla = vanillaFor(model);
        if (vanilla == null) {
            return null;
        }

        // The layer is named for the creature in all but a handful of cases, and where it is not
        // there is simply nothing to convert - which leaves the mob no worse off than before
        return layers.get(new ModelLayerLocation(vanilla, MAIN));
    }

    /**
     * Which vanilla creature is drawn with this model, if any.
     */
    @Nullable
    public static Identifier vanillaFor(@NotNull Object model) {
        Map<Class<?>, Identifier> known = byModel;
        if (known == null) {
            known = build();
            byModel = known;
        }

        return known.get(model.getClass());
    }

    /**
     * Every vanilla creature paired with the model class it is drawn with.
     * <p>
     * Read from the renderers rather than assumed, because which model a creature uses is the
     * renderer's business and several of them share one.
     */
    @NotNull
    private static Map<Class<?>, Identifier> build() {
        Map<Class<?>, Identifier> found = new HashMap<>();

        Map<EntityType<?>, EntityRenderer<?, ?>> renderers =
                ((EntityRenderDispatcherAccessor) Minecraft.getInstance().getEntityRenderDispatcher())
                        .hydraulic$renderers();

        for (Map.Entry<EntityType<?>, EntityRenderer<?, ?>> entry : renderers.entrySet()) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entry.getKey());
            if (key == null || !key.getNamespace().equals(VANILLA)) {
                continue;
            }

            if (!(entry.getValue() instanceof LivingEntityRenderer<?, ?, ?> living)) {
                continue;
            }

            Object model = living.getModel();
            if (model != null) {
                // The first one wins, and which one that is does not matter: two creatures drawn with
                // the same model are the same shape, which is the only thing being asked here
                found.putIfAbsent(model.getClass(), key);
            }
        }

        return found;
    }
}
