package org.geysermc.hydraulic.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.Map;

/** Reads the vanilla type declarations without constructing dummy mobs or running their hooks. */
public final class VanillaEntityClasses {
    private VanillaEntityClasses() {
    }

    @Nullable
    public static Class<? extends Entity> forType(EntityType<?> type) {
        return Types.CLASSES.get(type);
    }

    private static final class Types {
        private static final Map<EntityType<?>, Class<? extends Entity>> CLASSES = read();

        private static Map<EntityType<?>, Class<? extends Entity>> read() {
            Map<EntityType<?>, Class<? extends Entity>> result = new HashMap<>();
            for (var field : EntityTypes.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != EntityType.class
                        || !(field.getGenericType() instanceof ParameterizedType parameterized)
                        || !(parameterized.getActualTypeArguments()[0] instanceof Class<?> entityClass)
                        || !Entity.class.isAssignableFrom(entityClass)) {
                    continue;
                }
                try {
                    result.put((EntityType<?>) field.get(null), entityClass.asSubclass(Entity.class));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("Cannot read vanilla entity type " + field.getName(), exception);
                }
            }
            return Map.copyOf(result);
        }
    }
}
