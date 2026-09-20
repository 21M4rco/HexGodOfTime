package com.loki.client;

import com.loki.entity.ThrownDagger;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Where a buried blade actually is, frame by frame.
 *
 * <p>The impact point is stored once, as a fraction of the struck body's width and height inside that
 * body's own yaw frame, together with which part of it was hit. Rebuilding from fractions rather than
 * absolute metres is what keeps a chest wound in the chest when the creature is a baby, a boss or
 * something a mod invented, and the part tag is what lets an arm wound swing with the arm instead of
 * hanging in the air beside it.
 *
 * <p>Everything here is presentation: the entity's own position still tracks the body in world space
 * for the server's benefit, and this only refines where the mesh is drawn between ticks.
 */
public final class WoundAnchor {
    private WoundAnchor() {}

    /** Vanilla's limb swing: {@code cos(position * 0.6662) * amount}, scaled per limb. */
    private static final float SWING=.6662f;

    public static Vec3 lerpPosition(Entity e,float partial) {
        return new Vec3(Mth.lerp(partial,e.xo,e.getX()),Mth.lerp(partial,e.yo,e.getY()),Mth.lerp(partial,e.zo,e.getZ()));
    }

    /** The body yaw the offset was recorded against, interpolated for the frame being drawn. */
    public static float bodyYaw(Entity host,float partial) {
        if(host instanceof LivingEntity living)return Mth.rotLerp(partial,living.yBodyRotO,living.yBodyRot);
        return Mth.rotLerp(partial,host.yRotO,host.getYRot());
    }

    /** World position of the wound on {@code host}, including limb and head motion where it applies. */
    public static Vec3 world(ThrownDagger dagger,Entity host,float partial) {
        double width=Math.max(.1,host.getBbWidth()),height=Math.max(.1,host.getBbHeight());
        Vec3 unit=dagger.offset();
        Vec3 local=new Vec3(unit.x*width,unit.y*height,unit.z*width);
        local=animate(local,dagger.part(),host,height,partial);
        float yaw=bodyYaw(host,partial);
        double sin=Math.sin(-yaw*Mth.DEG_TO_RAD),cos=Math.cos(-yaw*Mth.DEG_TO_RAD);
        Vec3 rotated=new Vec3(local.x*cos+local.z*sin,local.y,-local.x*sin+local.z*cos);
        return lerpPosition(host,partial).add(rotated);
    }

    /** Extra yaw the blade itself inherits: only a skull turns the steel with it. */
    public static float turn(ThrownDagger dagger,Entity host,float partial) {
        if(dagger.part()!=ThrownDagger.HEAD||!(host instanceof LivingEntity living))return 0;
        return Mth.wrapDegrees(Mth.rotLerp(partial,living.yHeadRotO,living.yHeadRot)-bodyYaw(host,partial));
    }

    /**
     * Swings the anchor with whatever it is embedded in. The pivots are derived from the body's own
     * proportions rather than from a player skeleton, so a blade in the foreleg of something four
     * blocks tall still travels with that leg.
     */
    private static Vec3 animate(Vec3 local,int part,Entity host,double height,float partial) {
        if(part==ThrownDagger.TORSO||!(host instanceof LivingEntity living))return local;
        if(part==ThrownDagger.HEAD) {
            float head=Mth.wrapDegrees(Mth.rotLerp(partial,living.yHeadRotO,living.yHeadRot)-bodyYaw(host,partial));
            float pitch=Mth.lerp(partial,living.xRotO,living.getXRot());
            Vec3 pivot=new Vec3(0,height*.76,0);
            return pivot.add(rotate(local.subtract(pivot),head*Mth.DEG_TO_RAD,pitch*Mth.DEG_TO_RAD));
        }
        float position=living.walkAnimation.position(partial);
        float amount=Math.min(1,living.walkAnimation.speed(partial));
        boolean arm=part==ThrownDagger.RIGHT_ARM||part==ThrownDagger.LEFT_ARM;
        boolean right=part==ThrownDagger.RIGHT_ARM||part==ThrownDagger.RIGHT_LEG;
        float phase=Mth.cos(position*SWING+(right==arm?(float)Math.PI:0))*amount*(arm?1f:1.4f);
        Vec3 pivot=arm
            ?new Vec3((right?-1:1)*host.getBbWidth()*.42,height*.72,0)
            :new Vec3((right?-1:1)*host.getBbWidth()*.18,height*.42,0);
        return pivot.add(rotate(local.subtract(pivot),0,phase));
    }

    /** Yaw about the body axis then pitch about the side axis, both in radians. */
    private static Vec3 rotate(Vec3 v,double yaw,double pitch) {
        double cy=Math.cos(yaw),sy=Math.sin(yaw);
        Vec3 turned=new Vec3(v.x*cy-v.z*sy,v.y,v.x*sy+v.z*cy);
        double cp=Math.cos(pitch),sp=Math.sin(pitch);
        return new Vec3(turned.x,turned.y*cp-turned.z*sp,turned.y*sp+turned.z*cp);
    }
}
