package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.WarpHazard;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;

public final class WarpHazardRenderer extends EntityRenderer<WarpHazard> {
    public WarpHazardRenderer(EntityRendererProvider.Context c){super(c);}
    public ResourceLocation getTextureLocation(WarpHazard e){return HexGodOfStories.id("textures/white.png");}
    @Override public void render(WarpHazard e,float yaw,float partial,PoseStack p,MultiBufferSource source,int light){
        if(source instanceof MultiBufferSource.BufferSource buffers)buffers.endBatch();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);RenderSystem.disableCull();
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        WarpScene.hazard(b,p.last().pose(),e.kind(),0,0,0);BufferUploader.drawWithShader(b.end());RenderSystem.enableCull();
    }
}
