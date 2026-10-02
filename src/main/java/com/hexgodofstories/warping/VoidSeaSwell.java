package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Builds the Void Sea's swell ({@link VoidSea#swell}) out of water, a chunk at a time, the first time each chunk is
 * loaded, whether it was generated a moment ago or long before the sea had a shape at all.
 *
 * <p>The realm is still a flat world underneath, nine hundred and thirty five blocks of water on a floor of
 * Nothingness; this stands the swell up on top of it. Each column gets whole water up to the swell's height and, for
 * the part of a block that is left over, water with less in it, so the surface follows the swell to an eighth of a
 * block and the slopes come down smooth rather than in steps. The realm's water never flows (see VoidSeaStillMixin),
 * so the shape stays exactly where it was put; nothing here runs again for that chunk.
 *
 * <p>Done on the server thread as the chunk is promoted to a full chunk, before any player is sent it, straight into
 * the chunk's sections: no block updates, no neighbour notifications, no lighting work (the realm has no sky to
 * light, and water gives none off). A chunk carries the mark that it has been built in its very bottom block, under
 * eighty three blocks of unbreakable Nothingness, where nothing can reach it to take it away. Only air is ever
 * filled, so nothing anybody built is touched.
 */
@Mod.EventBusSubscriber(modid = HexGodOfStories.ID)
public final class VoidSeaSwell {
    private VoidSeaSwell() { }

    /** The mark a chunk carries once its swell is built, in the realm's lowest block. */
    private static final BlockState BUILT = Blocks.BEDROCK.defaultBlockState();

    @SubscribeEvent public static void loaded(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ServerLevel level)) return;
        if (Destination.from(level) != Destination.VOID_SEA) return;
        build(chunk);
    }

    static void build(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int bottom = chunk.getMinBuildHeight();
        LevelChunkSection[] sections = chunk.getSections();
        int markIndex = chunk.getSectionIndex(bottom);
        if (markIndex < 0 || markIndex >= sections.length) return;
        LevelChunkSection markSection = sections[markIndex];
        if (markSection.getBlockState(0, bottom & 15, 0).is(Blocks.BEDROCK)) return;
        BlockState still = Blocks.WATER.defaultBlockState();
        Heightmap[] maps = {chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE),
            chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING),
            chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)};
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++) {
                double swell = VoidSea.swell(pos.getMinBlockX() + dx + .5, pos.getMinBlockZ() + dz + .5);
                int whole = (int) Math.floor(swell), part = (int) Math.round((swell - whole) * 8);
                if (part >= 8) {whole++; part = 0;}
                int top = -1;
                for (int i = 1; i <= whole; i++)
                    if (fill(chunk, sections, dx, VoidSea.SURFACE + i, dz, still)) top = VoidSea.SURFACE + i;
                // The rest of a block: water with that much less in it (eight parts make a whole block).
                if (part > 0 && fill(chunk, sections, dx, VoidSea.SURFACE + whole + 1, dz, still.setValue(LiquidBlock.LEVEL, 8 - part)))
                    top = VoidSea.SURFACE + whole + 1;
                if (top >= 0) for (Heightmap map : maps) map.update(dx, top, dz, still);
            }
        markSection.setBlockState(0, bottom & 15, 0, BUILT, false);
        chunk.setUnsaved(true);
    }

    /** Water into an empty block of this column, and only an empty one. */
    private static boolean fill(LevelChunk chunk, LevelChunkSection[] sections, int x, int y, int z, BlockState water) {
        int index = chunk.getSectionIndex(y);
        if (index < 0 || index >= sections.length) return false;
        LevelChunkSection section = sections[index];
        if (!section.getBlockState(x, y & 15, z).isAir()) return false;
        section.setBlockState(x, y & 15, z, water, false);
        return true;
    }
}
