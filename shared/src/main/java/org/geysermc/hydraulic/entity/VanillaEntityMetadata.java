package org.geysermc.hydraulic.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates vanilla tracked fields back to their unmodified wire indices. Mods adding fields to
 * Entity/LivingEntity shift the fields of every subclass, even mobs whose type is still vanilla.
 * Only accessor names and serializers declared in the original Minecraft class are trusted.
 */
public final class VanillaEntityMetadata {
    private static final String SYNCHED = "net/minecraft/network/syncher/SynchedEntityData";
    private static final String SERIALIZERS = "net/minecraft/network/syncher/EntityDataSerializers";
    private static final String ACCESSOR = "Lnet/minecraft/network/syncher/EntityDataAccessor;";
    private static final Layout EMPTY = new Layout(Map.of(), 0, false);
    private static final ClassValue<Layout> LAYOUTS = new ClassValue<>() {
        @Override
        protected Layout computeValue(Class<?> type) {
            return readLayout(type);
        }
    };

    private VanillaEntityMetadata() {
    }

    public static List<SynchedEntityData.DataValue<?>> sanitize(Class<? extends Entity> type,
                                                               List<SynchedEntityData.DataValue<?>> values) {
        return sanitize(LAYOUTS.get(type).fields(), values);
    }

    record Binding(int wireId, EntityDataSerializer<?> serializer) {
    }

    private record Layout(Map<Integer, Binding> fields, int fieldCount, boolean known) {
    }

    static List<SynchedEntityData.DataValue<?>> sanitize(Map<Integer, Binding> fields,
                                                       List<SynchedEntityData.DataValue<?>> values) {
        List<SynchedEntityData.DataValue<?>> safe = new ArrayList<>(values.size());
        boolean changed = false;
        for (SynchedEntityData.DataValue<?> value : values) {
            Binding binding = fields.get(value.id());
            if (binding == null || binding.serializer() != value.serializer()) {
                changed = true;
                continue;
            }
            changed |= binding.wireId() != value.id();
            safe.add(binding.wireId() == value.id() ? value : reindex(value, binding.wireId()));
        }
        return changed ? safe : values;
    }

    private static <T> SynchedEntityData.DataValue<T> reindex(SynchedEntityData.DataValue<T> value, int id) {
        return new SynchedEntityData.DataValue<>(id, value.serializer(), value.value());
    }

    private static Layout readLayout(Class<?> type) {
        Layout parent = type == Entity.class ? new Layout(Map.of(), 0, true) : LAYOUTS.get(type.getSuperclass());
        if (!parent.known() || !type.getName().startsWith("net.minecraft.")) {
            return parent;
        }
        String owner = type.getName().replace('.', '/');
        try (var stream = type.getResourceAsStream("/" + owner + ".class")) {
            if (stream == null) {
                throw new IOException("Missing original Minecraft class " + owner);
            }
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            Map<Integer, Binding> fields = new HashMap<>(parent.fields());
            int index = parent.fieldCount();
            for (var method : node.methods) {
                if (!method.name.equals("<clinit>")) {
                    continue;
                }
                String serializer = null;
                boolean defining = false;
                for (var instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                            && field.owner.equals(SERIALIZERS)) {
                        serializer = field.name;
                    } else if (instruction instanceof MethodInsnNode call && call.owner.equals(SYNCHED)
                            && call.name.equals("defineId")) {
                        if (defining || serializer == null) {
                            throw new IOException("Unrecognised tracked-field definition in " + owner);
                        }
                        defining = true;
                    } else if (defining && instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.PUTSTATIC && field.owner.equals(owner)
                            && field.desc.equals(ACCESSOR)) {
                        var liveField = type.getDeclaredField(field.name);
                        if (!liveField.trySetAccessible()) {
                            throw new IllegalAccessException(field.name);
                        }
                        var accessor = (EntityDataAccessor<?>) liveField.get(null);
                        var expected = EntityDataSerializers.class.getDeclaredField(serializer).get(null);
                        if (accessor.serializer() != expected || index > 254 || fields.containsKey(accessor.id())) {
                            throw new IOException("Ambiguous tracked-field layout in " + owner);
                        }
                        fields.put(accessor.id(), new Binding(index++, accessor.serializer()));
                        defining = false;
                        serializer = null;
                    }
                }
                if (defining) {
                    throw new IOException("Unstored tracked-field definition in " + owner);
                }
            }
            return new Layout(Map.copyOf(fields), index, true);
        } catch (IOException | ReflectiveOperationException | RuntimeException exception) {
            // Sending an empty metadata list is valid. Guessing an index or serializer is not.
            LogUtils.getLogger().warn("Cannot recover vanilla tracked fields for {}; omitting its metadata for Bedrock", owner, exception);
            return EMPTY;
        }
    }
}
