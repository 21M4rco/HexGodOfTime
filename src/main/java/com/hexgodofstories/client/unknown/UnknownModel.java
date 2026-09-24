package com.hexgodofstories.client.unknown;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.unknown.UnknownEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/** The supplied Blockbench creature, with a speed-responsive tail and head overlay. */
public final class UnknownModel extends GeoModel<UnknownEntity> {
    @Override public ResourceLocation getModelResource(UnknownEntity e){return HexGodOfStories.id("geo/unknown.geo.json");}
    @Override public ResourceLocation getTextureResource(UnknownEntity e){return HexGodOfStories.id("textures/entity/unknown.png");}
    @Override public ResourceLocation getAnimationResource(UnknownEntity e){return HexGodOfStories.id("animations/unknown.animation.json");}
    @Override public void setCustomAnimations(UnknownEntity e,long instance,AnimationState<UnknownEntity> state){
        super.setCustomAnimations(e,instance,state);
        float speed=e.speed(),time=e.tickCount+state.getPartialTick();
        for(int i=1;i<=13;i++){
            var tail=getAnimationProcessor().getBone("tail_"+i);
            if(tail!=null)tail.setRotY(tail.getRotY()+
                    (float)Math.sin(time*(speed>.48?.48:.14)-i*.46)*(.015f+speed*.042f));
        }
        var head=getAnimationProcessor().getBone("head");
        if(head!=null&&e.phase()==1&&e.action()==0)
            head.setRotZ(head.getRotZ()+(float)Math.sin(time*.06)*(.008f+speed*.027f));
    }
}
