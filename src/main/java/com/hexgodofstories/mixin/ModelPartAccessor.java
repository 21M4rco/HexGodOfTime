package com.hexgodofstories.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** A part's children by name: a model built as one tree names its limbs there (client Halving). */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
    @Accessor("children") Map<String, ModelPart> hgos$children();
}
