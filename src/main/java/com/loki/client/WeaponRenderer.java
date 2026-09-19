package com.loki.client;
import com.loki.entity.ConjuredWeapon;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.item.*;
import java.util.*;

public final class WeaponRenderer extends BlockEntityWithoutLevelRenderer {
    private static WeaponRenderer INSTANCE;
    private static final Map<Integer,AuthoredMesh> MODELS=new HashMap<>();
    public WeaponRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    public static WeaponRenderer instance(){if(INSTANCE==null)INSTANCE=new WeaponRenderer();return INSTANCE;}
    public static void clear(){MODELS.clear();}
    public static void draw(int kind,PoseStack pose,MultiBufferSource buffers,int light,float growth){AuthoredMesh mesh=MODELS.computeIfAbsent(kind,k->new AuthoredMesh(k==1?"laevateinn":k==2?"time_stick":"dagger"));mesh.drawManifesting(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(LokiLayer.MATERIAL)),light,growth,0);}
    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(!(stack.getItem() instanceof ConjuredWeapon w))return;pose.pushPose();pose.translate(.5,.5,.5);
        float growth=1;boolean gui=context==ItemDisplayContext.GUI||context==ItemDisplayContext.GROUND||context==ItemDisplayContext.FIXED;
        if(stack.hasTag()&&stack.getTag().contains("formed")&&!gui)growth=Math.max(.05f,Math.min(1,(ClientState.now()+Minecraft.getInstance().getFrameTime()-stack.getTag().getLong("formed"))/12f));
        draw(w.kind,pose,buffers,light,growth);pose.popPose();
    }
}
