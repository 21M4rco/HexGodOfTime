package com.hexgodofstories.data;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether a block reads as soft natural cover — soil, sand, gravel, snow, leaves, flowers, crops and the
 * loose growth between them — as opposed to stone, wood, metal or something somebody built with.
 *
 * <p>This no longer decides what a Time Branch torrent takes: the torrent passes through everything and
 * puts it all back, so there is nothing to be permitted or refused. What it decides is how a block
 * <em>looks</em> as it goes. Turf comes apart into dust and the beam carries it away; a wall comes apart
 * into fragments. One test, read on the client, for that difference alone.
 *
 * <p>It stays an allow list rather than a hardness threshold because a threshold would call glass, rails
 * and redstone soft, and they plainly are not.
 */
public final class SoftTerrain {
    private SoftTerrain() {}

    /** Past this a block is structural whatever else it looks like, so it sheds fragments rather than dust. */
    private static final float LOOSE_HARDNESS=.7f;

    public static boolean soft(BlockGetter level,BlockPos pos,BlockState state) {
        if(state.isAir()||state.hasBlockEntity())return false;
        if(!state.getFluidState().isEmpty())return false;
        if(state.is(BlockTags.WITHER_IMMUNE)||state.is(BlockTags.DRAGON_IMMUNE))return false;
        // Cheap and fragile, but somebody put it there deliberately: glass, panes, rails, wiring, doors.
        if(state.is(BlockTags.IMPERMEABLE)||state.is(BlockTags.RAILS)||state.is(BlockTags.DOORS)
            ||state.is(BlockTags.TRAPDOORS)||state.is(BlockTags.BUTTONS)||state.is(BlockTags.WOOL)
            ||state.is(BlockTags.WOOL_CARPETS)||state.is(BlockTags.CANDLES)||state.is(BlockTags.WALLS)
            ||state.is(BlockTags.FENCES)||state.is(BlockTags.SLABS)||state.is(BlockTags.STAIRS)
            ||state.is(Blocks.GLASS_PANE)||state.is(Blocks.TINTED_GLASS)||state.is(Blocks.REDSTONE_WIRE)
            ||state.is(Blocks.LEVER)||state.is(Blocks.TRIPWIRE)||state.is(Blocks.SCAFFOLDING)
            ||state.is(Blocks.LADDER)||state.is(Blocks.TORCH)||state.is(Blocks.SOUL_TORCH)
            ||state.is(Blocks.REDSTONE_TORCH)||state.is(Blocks.LANTERN)||state.is(Blocks.SOUL_LANTERN)
            ||state.is(Blocks.SPAWNER)||state.is(Blocks.OBSIDIAN)||state.is(Blocks.CRYING_OBSIDIAN))return false;

        if(natural(state))return true;
        // Whatever a mod calls its soil, sand or undergrowth: the tool that clears it, plus a hardness
        // low enough that no structural material of theirs is caught by it either.
        if(!state.is(BlockTags.MINEABLE_WITH_HOE)&&!state.is(BlockTags.MINEABLE_WITH_SHOVEL))return false;
        float hardness=state.getDestroySpeed(level,pos);
        return hardness>=0&&hardness<=LOOSE_HARDNESS;
    }

    /** The named soft cover, by tag where vanilla has one and by block where it does not. */
    private static boolean natural(BlockState state) {
        return state.is(BlockTags.DIRT)||state.is(BlockTags.SAND)||state.is(BlockTags.LEAVES)
            ||state.is(BlockTags.FLOWERS)||state.is(BlockTags.SAPLINGS)||state.is(BlockTags.CROPS)
            ||state.is(BlockTags.SNOW)||state.is(BlockTags.SMALL_FLOWERS)||state.is(BlockTags.TALL_FLOWERS)
            ||state.is(BlockTags.REPLACEABLE_BY_TREES)||state.is(BlockTags.CAVE_VINES)
            ||state.is(BlockTags.SWORD_EFFICIENT)&&!state.is(BlockTags.MINEABLE_WITH_AXE)
            ||state.is(Blocks.GRAVEL)||state.is(Blocks.SUSPICIOUS_GRAVEL)||state.is(Blocks.SUSPICIOUS_SAND)
            ||state.is(Blocks.CLAY)||state.is(Blocks.MUD)||state.is(Blocks.SOUL_SAND)||state.is(Blocks.SOUL_SOIL)
            ||state.is(Blocks.MOSS_BLOCK)||state.is(Blocks.MOSS_CARPET)||state.is(Blocks.SNOW_BLOCK)
            ||state.is(Blocks.POWDER_SNOW)||state.is(Blocks.VINE)||state.is(Blocks.GLOW_LICHEN)
            ||state.is(Blocks.SUGAR_CANE)||state.is(Blocks.BAMBOO)||state.is(Blocks.LILY_PAD)
            ||state.is(Blocks.SEAGRASS)||state.is(Blocks.KELP)||state.is(Blocks.DEAD_BUSH)
            ||state.is(Blocks.SWEET_BERRY_BUSH)||state.is(Blocks.CACTUS)||state.is(Blocks.PUMPKIN)
            ||state.is(Blocks.MELON)||state.is(Blocks.HANGING_ROOTS)||state.is(Blocks.MANGROVE_ROOTS)
            ||state.is(Blocks.SPORE_BLOSSOM)||state.is(Blocks.PINK_PETALS)||state.is(Blocks.BIG_DRIPLEAF)
            ||state.is(Blocks.SMALL_DRIPLEAF)||state.is(Blocks.AZALEA)||state.is(Blocks.FLOWERING_AZALEA)
            ||state.is(Blocks.BROWN_MUSHROOM)||state.is(Blocks.RED_MUSHROOM)||state.is(Blocks.NETHER_WART)
            ||state.is(Blocks.WARPED_ROOTS)||state.is(Blocks.CRIMSON_ROOTS)||state.is(Blocks.WARPED_FUNGUS)
            ||state.is(Blocks.CRIMSON_FUNGUS)||state.is(Blocks.TWISTING_VINES)||state.is(Blocks.WEEPING_VINES)
            ||state.is(Blocks.FARMLAND)||state.is(Blocks.MYCELIUM)||state.is(Blocks.TURTLE_EGG);
    }
}
