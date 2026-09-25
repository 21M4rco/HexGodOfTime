package com.hexgodofstories.data;

import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Shared world-space presentation points for the V4 Scepter.
 *
 * The firing animation has the main arm extended toward the crosshair.  These offsets place the
 * muzzle at the blue crystal at the end of that pose rather than at the player's eyes or hand.
 */
public final class ScepterPose {
    private ScepterPose() { }

    public static Vec3 stoneMuzzle(LivingEntity caster) {
        Vec3 forward=caster.getLookAngle();
        if(forward.lengthSqr()>1.0E-8)forward=forward.normalize();
        Vec3 right=new Vec3(-forward.z,0,forward.x);
        if(right.lengthSqr()>1.0E-8)right=right.normalize();
        else right=new Vec3(1,0,0);
        double side=caster.getMainArm()==HumanoidArm.RIGHT?1:-1;
        return caster.getEyePosition()
            .add(forward.scale(1.10))
            .add(right.scale(.28*side))
            .add(0,-.25,0);
    }
}
