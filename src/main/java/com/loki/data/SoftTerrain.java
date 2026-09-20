package com.loki.data;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * What raw temporal radiation can take out of the timeline, and what it cannot.
 *
 * <p>This is an allow list on purpose. A hardness threshold would read as reasonable and then quietly
 * delete glass, rails, redstone and every other cheap thing somebody built with, so nothing qualifies
 * here unless it is soft natural cover: soil, sand, gravel, snow, leaves, flowers, crops and the loose
 * growth between them. Stone, wood, ore, metal and anything holding a block entity survive, which is
 * what keeps the ultimate from carving a permanent tunnel through a mountain or erasing a hall.
 *
 * <p>Both sides read the same test. The server decides what actually leaves the world and the client
 * decides what to dissolve on screen, so the two agree without a packet per block.
 */
public final class SoftTerrain {
    private SoftTerrain() {}

    /** Steps along the axis and across each slice, in blocks. Fine enough not to miss a block. */
    private static final double SLICE=.6,GRID=.6;

    /**
     * The soft cover inside a cylinder, ordered by how far along the axis it sits.
     *
     * <p>Walked as slices down the axis rather than over the segment's bounding box. That is not a
     * micro-optimisation: a sixty-block beam pointed diagonally has a bounding box of some eighty
     * thousand positions, so a box walk would both cost a visible hitch and, once it hit any cap,
     * truncate the result along the world axes instead of along the beam — erasing a wedge of ground
     * and leaving the rest. Slicing keeps the cost flat whichever way the caster is facing, and an
     * over-long beam simply stops taking cover at its far end.
     *
     * <p>Both sides call this, so what the server removes and what the client dissolves are the same
     * list in the same order, with no packet per block.
     */
    public static List<BlockPos> cylinder(BlockGetter level,Vec3 origin,Vec3 direction,
                                          double from,double to,double radius,int cap) {
        List<BlockPos> found=new ArrayList<>();
        if(to<=from||radius<=0)return found;
        Vec3 axis=direction.normalize();
        Vec3 side=axis.cross(new Vec3(0,1,0));
        if(side.lengthSqr()<1e-6)side=axis.cross(new Vec3(1,0,0));
        if(side.lengthSqr()<1e-6)side=new Vec3(1,0,0);
        side=side.normalize();
        Vec3 up=side.cross(axis).normalize();
        Set<BlockPos> seen=new HashSet<>();
        double radiusSq=radius*radius;
        for(double t=from;t<=to;t+=SLICE) {
            Vec3 centre=origin.add(axis.scale(t));
            for(double a=-radius;a<=radius;a+=GRID)for(double b=-radius;b<=radius;b+=GRID) {
                if(a*a+b*b>radiusSq)continue;
                BlockPos pos=BlockPos.containing(centre.add(side.scale(a)).add(up.scale(b)));
                if(!seen.add(pos))continue;
                if(soft(level,pos,level.getBlockState(pos)))found.add(pos);
                if(found.size()>=cap)return found;
            }
        }
        return found;
    }

    /** Beyond this the block is structural whatever else it looks like. */
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
