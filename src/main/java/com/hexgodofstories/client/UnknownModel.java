package com.hexgodofstories.client;
import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.entity.UnknownEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
public final class UnknownModel extends GeoModel<UnknownEntity> {
    @Override public ResourceLocation getModelResource(UnknownEntity e){return HexGodOfStories.id("geo/unknown.geo.json");}
    @Override public ResourceLocation getTextureResource(UnknownEntity e){return HexGodOfStories.id("textures/entity/unknown.png");}
    @Override public ResourceLocation getAnimationResource(UnknownEntity e){return HexGodOfStories.id("animations/unknown.animation.json");}
}
