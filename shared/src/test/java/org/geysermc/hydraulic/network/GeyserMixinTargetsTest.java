package org.geysermc.hydraulic.network;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class GeyserMixinTargetsTest {
    @Test
    void serverSendWrapperMatchesMinecraftMethod() throws IOException {
        assertMethod("net/minecraft/server/network/ServerCommonPacketListenerImpl", "send",
                "(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V");
    }

    @Test
    void recipeComparatorInjectionMatchesRealGeyserMethod() throws IOException {
        String owner = "org/geysermc/geyser/inventory/recipe/RecipeUtil$ItemDescriptorWithCountComparator";
        String item = "Lorg/cloudburstmc/protocol/bedrock/data/inventory/descriptor/ItemDescriptorWithCount;";
        assertMethod(owner, "compare", "(" + item + item + ")I");
    }

    @Test
    void packetDiagnosticWrapMatchesActualDecoderCall() throws IOException {
        ClassNode codec = readClass("org/geysermc/mcprotocollib/network/netty/PacketCodec");
        boolean callFound = codec.methods.stream().filter(method -> method.name.equals("decode"))
                .flatMap(method -> java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false))
                .anyMatch(instruction -> instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals("org/geysermc/mcprotocollib/network/packet/PacketRegistry")
                        && call.name.equals("createClientboundPacket")
                        && call.desc.equals("(ILio/netty/buffer/ByteBuf;)Lorg/geysermc/mcprotocollib/network/packet/Packet;"));
        assertTrue(callFound);
    }

    private static void assertMethod(String owner, String name, String descriptor) throws IOException {
        assertTrue(readClass(owner).methods.stream().anyMatch(method ->
                method.name.equals(name) && method.desc.equals(descriptor)), owner + "." + name + descriptor);
    }

    private static ClassNode readClass(String owner) throws IOException {
        try (var stream = GeyserMixinTargetsTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(stream, owner);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }
}
