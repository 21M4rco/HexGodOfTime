package com.hexgodofstories.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;

import java.util.List;

/**
 * A block's model as chunk building sees it while a Scepter hole is open somewhere in blocks of its kind:
 * the same model, except that a block with a hole in it is nothing at all, so its chunk leaves it out and
 * BlockWounds can draw it carved.
 *
 * <p>This is how a hole leaves the mesh of a chunk renderer that does not build meshes through vanilla's
 * own call (Embeddium, for one): every renderer that honours Forge's models asks each block's model for
 * its data at that block, and meshes whatever quads that data then gives. Chunk building asks from a copy
 * of the world, never the level itself; anything drawn from the level — the block drawn carved, the cracks
 * of mining it, a falling block — gets the model exactly as it always was.
 */
final class HoleModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<Boolean> HOLED = new ModelProperty<>();

    HoleModel(BakedModel original) {super(original);}

    @Override public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        ModelData own = originalModel.getModelData(level, pos, state, data);
        if (level instanceof Level || !BlockWounds.hidden(pos)) return own;
        return own.derive().with(HOLED, Boolean.TRUE).build();
    }

    @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
        return Boolean.TRUE.equals(data.get(HOLED)) ? List.of() : originalModel.getQuads(state, side, random, data, type);
    }

    @Override public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return Boolean.TRUE.equals(data.get(HOLED)) ? ChunkRenderTypeSet.none() : originalModel.getRenderTypes(state, random, data);
    }
}
