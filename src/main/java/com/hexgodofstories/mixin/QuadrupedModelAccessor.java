package com.hexgodofstories.mixin;

import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A four-legged model's head and legs, for a half of one cut in two to go limp (client Halving). */
@Mixin(QuadrupedModel.class)
public interface QuadrupedModelAccessor {
    @Accessor("head") ModelPart hgos$head();
    @Accessor("rightHindLeg") ModelPart hgos$rightHindLeg();
    @Accessor("leftHindLeg") ModelPart hgos$leftHindLeg();
    @Accessor("rightFrontLeg") ModelPart hgos$rightFrontLeg();
    @Accessor("leftFrontLeg") ModelPart hgos$leftFrontLeg();
}
