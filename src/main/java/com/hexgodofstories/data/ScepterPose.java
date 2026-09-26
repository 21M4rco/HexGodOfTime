package com.hexgodofstories.data;

import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Shared world-space presentation points for the Scepter.
 *
 * In third person the staff is always carried low at the side, tip forward and toward the ground,
 * and never follows the caster's look. These offsets place the muzzle at the stone of that carry: in
 * the body's facing, just ahead of the hand at hip height, rather than at the player's eyes. The
 * caster's own first-person view draws the beam from the stone in the hand instead; see
 * ScepterFx.stone.
 */
public final class ScepterPose {
    private ScepterPose() { }

    public static Vec3 stoneMuzzle(LivingEntity caster) {
        Vec3 forward=Vec3.directionFromRotation(0,caster.yBodyRot);
        Vec3 right=new Vec3(-forward.z,0,forward.x);
        double side=caster.getMainArm()==HumanoidArm.RIGHT?1:-1;
        return caster.getEyePosition()
            .add(forward.scale(.45))
            .add(right.scale(.35*side))
            .add(0,-1.07,0);
    }
}
