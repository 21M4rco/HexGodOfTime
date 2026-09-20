package com.hexgodofstories.mixin;

import com.hexgodofstories.client.WoundAnchor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** Observe the posed part only for a body carrying one of the keeper's embedded daggers. */
@Mixin(ModelPart.class)
public abstract class WoundPartMixin {
    @Shadow @Final private List<ModelPart.Cube> cubes;
    @Inject(method="compile",at=@At("HEAD"))
    private void hgos$wound(PoseStack.Pose pose,VertexConsumer vertices,int light,int overlay,
                            float red,float green,float blue,float alpha,CallbackInfo ci) {
        WoundAnchor.capturePart(this,pose,cubes);
    }
}
