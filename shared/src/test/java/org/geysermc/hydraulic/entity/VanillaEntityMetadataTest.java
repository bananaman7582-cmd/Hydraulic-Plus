package org.geysermc.hydraulic.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VanillaEntityMetadataTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void shiftsInheritedFieldsDropsAddedFieldsAndChecksSerializerIdentity() {
        var health = new SynchedEntityData.DataValue<>(18, EntityDataSerializers.FLOAT, 20F);
        var added = new SynchedEntityData.DataValue<>(9, EntityDataSerializers.INT, 42);
        var wrongType = new SynchedEntityData.DataValue<>(0, EntityDataSerializers.INT, 0);
        var safe = VanillaEntityMetadata.sanitize(Map.of(
                18, new VanillaEntityMetadata.Binding(9, EntityDataSerializers.FLOAT),
                0, new VanillaEntityMetadata.Binding(0, EntityDataSerializers.BYTE)),
                List.of(health, added, wrongType));
        assertEquals(1, safe.size());
        assertEquals(9, safe.getFirst().id());
        assertSame(EntityDataSerializers.FLOAT, safe.getFirst().serializer());
        assertEquals(20F, safe.getFirst().value());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void allDeclaredVanillaEntityTypesAndTheirOriginalFieldsAreRecognised() throws Exception {
        int types = 0;
        for (var entityType : BuiltInRegistries.ENTITY_TYPE) {
            Class<? extends Entity> type = VanillaEntityClasses.forType(entityType);
            assertNotNull(type, BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString());
            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
            for (Class<?> current = type; current != null && Entity.class.isAssignableFrom(current);
                 current = current.getSuperclass()) {
                for (var field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && field.getType() == EntityDataAccessor.class) {
                        assertTrue(field.trySetAccessible());
                        EntityDataAccessor accessor = (EntityDataAccessor) field.get(null);
                        // The payload is not encoded in this layout test; only id and serializer matter.
                        values.add(new SynchedEntityData.DataValue<>(accessor.id(), accessor.serializer(), new Object()));
                    }
                }
            }
            assertFalse(values.isEmpty(), type.getName());
            assertSame(values, VanillaEntityMetadata.sanitize(type, values), type.getName());
            types++;
        }
        assertTrue(types > 150, "The complete vanilla entity registry was checked");
    }

    @Test
    void entityOnlyProjectionKeepsUniversalFieldsButNotUnknownSubclassFields() {
        var flags = new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, (byte) 1);
        var unknown = new SynchedEntityData.DataValue<>(200, EntityDataSerializers.INT, 12);
        assertEquals(List.of(flags), VanillaEntityMetadata.sanitize(Entity.class, List.of(flags, unknown)));
    }
}
