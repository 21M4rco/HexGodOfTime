package com.hexgodofstories.mixin;

import com.hexgodofstories.warping.MoonGravity;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class MoonCameraMixin {
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow protected abstract void setPosition(Vec3 position);
    @Inject(method="setup",at=@At("RETURN"))
    private void hgos$radialCamera(BlockGetter level,Entity entity,boolean thirdPerson,boolean reverse,float partial,CallbackInfo ci) {
        if(!MoonGravity.active(entity))return;
        Vec3 feet=new Vec3(net.minecraft.util.Mth.lerp(partial,entity.xo,entity.getX()),net.minecraft.util.Mth.lerp(partial,entity.yo,entity.getY()),net.minecraft.util.Mth.lerp(partial,entity.zo,entity.getZ()));
        rotation.premul(MoonGravity.rotation(entity,feet));
        forwards.set(0,0,1).rotate(rotation);up.set(0,1,0).rotate(rotation);left.set(1,0,0).rotate(rotation);
        Vec3 eye=MoonGravity.eye(entity,partial),at=eye;
        if(thirdPerson) {
            Vec3 look=new Vec3(forwards.x,forwards.y,forwards.z);
            // The moon is analytic, so vanilla's block ray cannot keep the camera out of it.
            for(double distance=.1;distance<=4;distance+=.1) {
                Vec3 candidate=eye.subtract(look.scale(distance));
                if(candidate.distanceTo(MoonGravity.CENTER)<MoonGravity.radius(MoonGravity.up(candidate))+.18)break;
                at=candidate;
            }
        }
        setPosition(at);
    }
}
