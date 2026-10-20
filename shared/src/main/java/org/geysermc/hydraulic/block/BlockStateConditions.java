package org.geysermc.hydraulic.block;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import team.unnamed.creative.blockstate.Condition;

import java.util.Arrays;
import java.util.function.Function;

/** Recursive multipart predicates using the property's serialized name, not its Java toString. */
final class BlockStateConditions {
    private BlockStateConditions() {
    }

    static boolean matches(Condition condition, BlockState state) {
        return matches(condition, name -> state.getProperties().stream()
                .filter(property -> property.getName().equals(name)).findFirst()
                .map(property -> value(state, property)).orElse(null));
    }

    static boolean matches(Condition condition, Function<String, String> properties) {
        if (condition == Condition.NONE) {
            return true;
        }
        if (condition instanceof Condition.And and) {
            return and.conditions().stream().allMatch(child -> matches(child, properties));
        }
        if (condition instanceof Condition.Or or) {
            return or.conditions().stream().anyMatch(child -> matches(child, properties));
        }
        if (condition instanceof Condition.Match match) {
            String value = properties.apply(match.key());
            return value != null && Arrays.stream(String.valueOf(match.value()).split("\\|"))
                    .anyMatch(value::equals);
        }
        return false;
    }

    static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
