package com.hexgodofstories.client;

import com.hexgodofstories.warping.Destination;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class WarpSky extends DimensionSpecialEffects {
    public WarpSky(){super(Float.NaN,false,SkyType.NONE,true,false);}
    /**
     * Fog comes from the biome now, for every realm but the sea.
     *
     * <p>This returned one hardcoded near black for all eight of the others, which is why they all
     * looked like the same purple void whatever their biome said: the incoming colour is the one
     * Minecraft has already derived from the biome's fog_color, and it was being thrown away. The
     * Void Sea keeps its hand set water colour because that realm is finished.
     */
    public Vec3 getBrightnessDependentFogColor(Vec3 color,float sun){
        var level=net.minecraft.client.Minecraft.getInstance().level;
        return level!=null&&Destination.from(level)==Destination.VOID_SEA?new Vec3(.025,.10,.24):color;
    }
    public boolean isFoggyAt(int x,int z){return false;}
    @Override public float[] getSunriseColor(float time,float partial){return null;}
    @Override public boolean renderClouds(ClientLevel l,int ticks,float p,PoseStack pose,double x,double y,double z,Matrix4f projection){return true;}
    @Override public boolean renderSnowAndRain(ClientLevel l,int ticks,float p,LightTexture light,double x,double y,double z){return true;}
    @Override public boolean tickRain(ClientLevel l,int ticks,Camera camera){return true;}
    @Override public boolean renderSky(ClientLevel l,int ticks,float partial,PoseStack pose,Camera camera,Matrix4f projection,boolean foggy,Runnable setupFog){
        Destination d=Destination.from(l);if(d==null||foggy)return true;
        RenderSystem.enableDepthTest();RenderSystem.depthMask(false);RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();FogRenderer.setupNoFog();
        pose.pushPose();
        try{
            if(d==Destination.SUN||d==Destination.VOID_SEA)
                RealmSky.drawBackdrop(pose,projection,ticks+partial,d==Destination.VOID_SEA?RealmSky.Palette.OCEAN:RealmSky.Palette.STELLAR);
            else{pose.scale(.3f,.3f,.3f);pose.translate(0,-110,0);WarpScene.sky(pose,d,ticks+partial);}
        }finally{pose.popPose();RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.depthMask(true);setupFog.run();}
        return true;
    }
}
