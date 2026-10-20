package org.geysermc.hydraulic.pack;

import net.kyori.adventure.key.Key;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.model.Model;

import java.util.HashMap;
import java.util.Map;

/** Indexes only models, allowing texture and sound data in the source packs to be released. */
final class ModelIndex {
    private ModelIndex() {
    }

    static ModelStitcher.Provider create(Iterable<ResourcePack> packs, ResourcePack vanilla) {
        Map<Key, Model> models = new HashMap<>();
        for (ResourcePack pack : packs) {
            for (Model model : pack.models()) {
                models.putIfAbsent(model.key(), model);
            }
        }
        for (Model model : vanilla.models()) {
            models.putIfAbsent(model.key(), model);
        }
        return models::get;
    }
}
