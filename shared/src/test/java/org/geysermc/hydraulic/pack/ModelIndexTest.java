package org.geysermc.hydraulic.pack;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.model.Model;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelIndexTest {
    @Test
    void keepsFirstModPrecedenceAndFallsBackToVanilla() {
        Key shared = Key.key("minecraft:block/shared");
        Model first = Model.model().key(shared).build();
        Model second = Model.model().key(shared).build();
        Model fallback = Model.model().key(Key.key("minecraft:block/fallback")).build();
        ResourcePack a = ResourcePack.resourcePack();
        ResourcePack b = ResourcePack.resourcePack();
        ResourcePack vanilla = ResourcePack.resourcePack();
        a.model(first);
        b.model(second);
        vanilla.model(second);
        vanilla.model(fallback);
        var provider = ModelIndex.create(List.of(a, b), vanilla);
        assertSame(first, provider.model(shared));
        assertSame(fallback, provider.model(fallback.key()));
        assertNull(provider.model(Key.key("missing:model")));
        // The provider holds the selected models, not the original ResourcePack containers.
        a.removeModel(shared);
        assertSame(first, provider.model(shared));
    }
}
