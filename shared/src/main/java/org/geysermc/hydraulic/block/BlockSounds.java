package org.geysermc.hydraulic.block;

import net.minecraft.world.level.block.SoundType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maps Java's block sound types onto the sounds Bedrock understands.
 * <p>
 * Bedrock takes a block's step/dig/place sounds from the {@code sound} entry that the resource pack's
 * {@code blocks.json} gives it, keyed by block identifier. Neither Geyser nor Hydraulic wrote that
 * file, so modded blocks had no sounds at all and the client logged
 * "No sound found for block type 'normal'".
 * <p>
 * Every name below is one Bedrock's own {@code blocks.json} uses, so none of them are guesses - an
 * unrecognised name would leave the block silent, which is the outcome this is meant to avoid.
 * Sound types Bedrock has no equivalent for fall back to the closest one it does have.
 */
public final class BlockSounds {
    private static final String STONE = "stone";

    private static final Map<SoundType, String> SOUNDS = new IdentityHashMap<>();

    static {
        // Stone and its many variants
        put("stone", SoundType.STONE);
        put("deepslate", SoundType.DEEPSLATE, SoundType.POLISHED_DEEPSLATE);
        put("deepslate_bricks", SoundType.DEEPSLATE_BRICKS, SoundType.DEEPSLATE_TILES);
        put("tuff", SoundType.TUFF);
        put("tuff_bricks", SoundType.TUFF_BRICKS);
        put("polished_tuff", SoundType.POLISHED_TUFF);
        put("calcite", SoundType.CALCITE);
        put("dripstone_block", SoundType.DRIPSTONE_BLOCK);
        put("pointed_dripstone", SoundType.POINTED_DRIPSTONE);
        put("basalt", SoundType.BASALT);
        put("netherrack", SoundType.NETHERRACK);
        put("nether_brick", SoundType.NETHER_BRICKS);
        put("nether_gold_ore", SoundType.NETHER_GOLD_ORE, SoundType.NETHER_ORE);
        put("bone_block", SoundType.BONE_BLOCK);
        put("lodestone", SoundType.LODESTONE);
        put("mud_bricks", SoundType.MUD_BRICKS);
        put("packed_mud", SoundType.PACKED_MUD);
        put("resin_brick", SoundType.RESIN_BRICKS);
        put("terracotta", SoundType.WART_BLOCK);

        // Metals - netherite has its own sound rather than sounding like plain metal
        put("metal", SoundType.METAL);
        put("netherite", SoundType.NETHERITE_BLOCK);
        put("ancient_debris", SoundType.ANCIENT_DEBRIS);
        put("copper", SoundType.COPPER, SoundType.COPPER_GOLEM_STATUE);
        put("copper_bulb", SoundType.COPPER_BULB);
        put("copper_grate", SoundType.COPPER_GRATE);
        put("chain", SoundType.CHAIN);
        put("iron", SoundType.IRON);
        put("anvil", SoundType.ANVIL);
        put("lantern", SoundType.LANTERN);

        // Wood
        put("wood", SoundType.WOOD, SoundType.HARD_CROP);
        put("bamboo_wood", SoundType.BAMBOO_WOOD);
        put("nether_wood", SoundType.NETHER_WOOD);
        put("cherry_wood", SoundType.CHERRY_WOOD);
        put("hanging_sign", SoundType.HANGING_SIGN);
        put("nether_wood_hanging_sign", SoundType.NETHER_WOOD_HANGING_SIGN);
        put("bamboo_wood_hanging_sign", SoundType.BAMBOO_WOOD_HANGING_SIGN);
        put("cherry_wood_hanging_sign", SoundType.CHERRY_WOOD_HANGING_SIGN);
        put("chiseled_bookshelf", SoundType.CHISELED_BOOKSHELF);
        put("shelf", SoundType.SHELF);
        put("bamboo", SoundType.BAMBOO);
        put("bamboo_sapling", SoundType.BAMBOO_SAPLING);
        put("scaffolding", SoundType.SCAFFOLDING);
        put("ladder", SoundType.LADDER);
        put("creaking_heart", SoundType.CREAKING_HEART);

        // Plants and other growth
        put("grass", SoundType.GRASS, SoundType.LILY_PAD, SoundType.CROP, SoundType.WET_GRASS);
        put("vines", SoundType.VINE);
        put("cave_vines", SoundType.CAVE_VINES);
        put("weeping_vines", SoundType.WEEPING_VINES, SoundType.TWISTING_VINES);
        put("roots", SoundType.ROOTS);
        put("hanging_roots", SoundType.HANGING_ROOTS);
        put("dirt_with_roots", SoundType.ROOTED_DIRT);
        put("mangrove_roots", SoundType.MANGROVE_ROOTS);
        put("muddy_mangrove_roots", SoundType.MUDDY_MANGROVE_ROOTS);
        put("fungus", SoundType.FUNGUS);
        put("shroomlight", SoundType.SHROOMLIGHT);
        put("nether_wart", SoundType.NETHER_WART);
        put("nether_sprouts", SoundType.NETHER_SPROUTS);
        put("stem", SoundType.STEM);
        put("nylium", SoundType.NYLIUM);
        put("sweet_berry_bush", SoundType.SWEET_BERRY_BUSH);
        put("azalea", SoundType.AZALEA, SoundType.FLOWERING_AZALEA);
        put("azalea_leaves", SoundType.AZALEA_LEAVES);
        put("cherry_leaves", SoundType.CHERRY_LEAVES, SoundType.CHERRY_SAPLING);
        put("big_dripleaf", SoundType.BIG_DRIPLEAF, SoundType.SMALL_DRIPLEAF);
        put("spore_blossom", SoundType.SPORE_BLOSSOM);
        put("cactus_flower", SoundType.CACTUS_FLOWER);
        put("pink_petals", SoundType.PINK_PETALS);
        put("leaf_litter", SoundType.LEAF_LITTER);
        put("moss_block", SoundType.MOSS);
        put("moss_carpet", SoundType.MOSS_CARPET);
        put("glow_lichen", SoundType.GLOW_LICHEN);
        put("coral", SoundType.CORAL_BLOCK);
        put("sponge", SoundType.SPONGE);
        put("wet_sponge", SoundType.WET_SPONGE);
        put("web", SoundType.COBWEB);

        // Loose ground
        put("sand", SoundType.SAND);
        put("suspicious_sand", SoundType.SUSPICIOUS_SAND);
        put("suspicious_gravel", SoundType.SUSPICIOUS_GRAVEL);
        put("gravel", SoundType.GRAVEL);
        put("soul_sand", SoundType.SOUL_SAND);
        put("soul_soil", SoundType.SOUL_SOIL);
        put("mud", SoundType.MUD);
        put("snow", SoundType.SNOW);
        put("powder_snow", SoundType.POWDER_SNOW);

        // Sculk
        put("sculk", SoundType.SCULK);
        put("sculk_catalyst", SoundType.SCULK_CATALYST);
        put("sculk_sensor", SoundType.SCULK_SENSOR);
        put("sculk_shrieker", SoundType.SCULK_SHRIEKER);
        put("sculk_vein", SoundType.SCULK_VEIN);

        // Amethyst
        put("amethyst_block", SoundType.AMETHYST);
        put("amethyst_cluster", SoundType.AMETHYST_CLUSTER);
        put("small_amethyst_bud", SoundType.SMALL_AMETHYST_BUD);
        put("medium_amethyst_bud", SoundType.MEDIUM_AMETHYST_BUD);
        put("large_amethyst_bud", SoundType.LARGE_AMETHYST_BUD);

        // Everything else with a direct Bedrock equivalent
        put("cloth", SoundType.WOOL);
        put("glass", SoundType.GLASS);
        put("slime", SoundType.SLIME_BLOCK);
        put("honey_block", SoundType.HONEY_BLOCK);
        put("candle", SoundType.CANDLE);
        put("froglight", SoundType.FROGLIGHT);
        put("frog_spawn", SoundType.FROGSPAWN);
        put("decorated_pot", SoundType.DECORATED_POT, SoundType.DECORATED_POT_CRACKED);
        put("trial_spawner", SoundType.TRIAL_SPAWNER);
        put("vault", SoundType.VAULT);
        put("heavy_core", SoundType.HEAVY_CORE);
        put("mob_spawner", SoundType.SPAWNER);
        put("resin", SoundType.RESIN);
        put("dried_ghast", SoundType.DRIED_GHAST);
        put("sulfur", SoundType.SULFUR);
        put("potent_sulfur", SoundType.POTENT_SULFUR);
        put("sulfur_spike", SoundType.SULFUR_SPIKE);
        put("cinnabar", SoundType.CINNABAR);
    }

    private BlockSounds() {
    }

    private static void put(@NotNull String bedrockSound, @NotNull SoundType... types) {
        for (SoundType type : types) {
            SOUNDS.put(type, bedrockSound);
        }
    }

    /**
     * Gets the Bedrock sound to use for the given Java sound type.
     *
     * @param soundType the Java sound type
     * @return the Bedrock sound name, or {@code null} if the block should stay silent
     */
    @Nullable
    public static String bedrockSound(@NotNull SoundType soundType) {
        if (soundType == SoundType.EMPTY) {
            return null;
        }

        // Anything unrecognised - including sound types added by newer Minecraft versions or by
        // mods - falls back to stone rather than being left silent.
        return SOUNDS.getOrDefault(soundType, STONE);
    }
}
