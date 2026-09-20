package com.hexgodofstories.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * The absence a Time Branch torrent leaves behind it.
 *
 * <p>Absolute black, and by construction rather than by choosing a dark paint. Block rendering multiplies
 * the texture by the light map and by the face shading, and anything multiplied by zero is zero, so a
 * texture of pure black renders as pure black at every light level and from every angle — darker than
 * black concrete or black wool, which are dark greys being lit. There is no detail in the sheet at all, so
 * a face of it reads as a hole rather than as a surface.
 *
 * <p>It is unbreakable, has no loot table and no item form, so it cannot be mined, blown up, pushed,
 * picked or obtained. It exists only for the half-minute between a torrent passing through something and
 * {@link com.hexgodofstories.server.Nothingness} putting that something back.
 *
 * <p>It is deliberately <em>solid</em>, with collision. A hole you could walk into would be a hole you
 * could be standing inside when the wall comes back, and being restored into is worse than being walled
 * out of. For the same reason it neither suffocates nor blocks the view: if something does end up inside
 * one, it is inconvenienced rather than killed.
 */
public final class NothingnessBlock extends Block {
    public NothingnessBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BLACK)
            // Bedrock's numbers: unbreakable by tool and immune to every explosion.
            .strength(-1,3600000)
            .noLootTable()
            .sound(SoundType.EMPTY)
            .lightLevel(state->0)
            .isValidSpawn((state,level,pos,type)->false)
            .isSuffocating((state,level,pos)->false)
            .isViewBlocking((state,level,pos)->false)
            .pushReaction(PushReaction.BLOCK));
    }

    /** Nothing to pick up, so middle-clicking it hands back nothing. */
    @Override public ItemStack getCloneItemStack(BlockGetter level,BlockPos pos,BlockState state) {return ItemStack.EMPTY;}
    @Override public boolean isRandomlyTicking(BlockState state) {return false;}
    /** Nothing routes through it, so no creature tries to path into a hole that is about to close. */
    @Override public boolean isPathfindable(BlockState state,BlockGetter level,BlockPos pos,net.minecraft.world.level.pathfinder.PathComputationType type) {return false;}
}
