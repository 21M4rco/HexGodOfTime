package com.hexgodofstories.warping;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Paradise's boundary and safe returns also apply to flying players, mounts and loose items. */
public final class ParadiseRules {
    private ParadiseRules() { }

    public static void enforce(ServerLevel level,Entity entity) {
        if(entity.isPassenger()||WarpCrossing.crossing(entity))return;
        if(entity.getY()<Paradise.FLOOR) {
            Vec3 safe=landing(level,entity);
            moveTree(entity,safe);
            entity.setDeltaMovement(Vec3.ZERO);
        } else {
            Vec3 folded=Paradise.fold(entity.position());
            if(folded.equals(entity.position()))return;
            Vec3 velocity=entity.getDeltaMovement();
            moveTree(entity,folded);
            // Cancel outward travel so a fast flyer cannot immediately bounce against the boundary.
            Vec3 normal=new Vec3(folded.x,0,folded.z).normalize();
            double outward=Math.max(0,velocity.dot(normal));
            entity.setDeltaMovement(velocity.subtract(normal.scale(outward))
                .multiply(1,entity.getY()>=Paradise.CEILING-Paradise.FOLD_INSET?0:1,1));
        }
        if(entity instanceof Mob mob)mob.getNavigation().stop();
        entity.hurtMarked=true;
    }

    public static Vec3 landing(ServerLevel level,Entity entity) {
        // Check actual support and the entire bounding box: modded mobs may be wider than a player.
        for(int ring=0;ring<=24;ring+=2)for(int dx=-ring;dx<=ring;dx+=2)for(int dz=-ring;dz<=ring;dz+=2) {
            if(ring>0&&Math.abs(dx)!=ring&&Math.abs(dz)!=ring)continue;
            double x=Paradise.RESCUE.x+dx,z=Paradise.RESCUE.z+dz;
            if(Paradise.inland(Paradise.heart(),x,z)<entity.getBbWidth()/2+1)continue;
            for(int lift=0;lift<=48;lift++) {
                Vec3 at=new Vec3(x,Paradise.RESCUE.y+lift,z);
                BlockPos below=BlockPos.containing(at).below();
                if(!level.getBlockState(below).isFaceSturdy(level,below,Direction.UP))continue;
                if(!level.getFluidState(below.above()).isEmpty())continue;
                if(level.noCollision(entity,entity.getBoundingBox().move(at.subtract(entity.position()))))return at;
            }
        }
        // If the whole landing garden has been mined, restore a small support pad before returning.
        int radius=Math.max(2,(int)Math.ceil(entity.getBbWidth()/2)+1);
        for(int lift=0;lift<=96;lift++) {
            Vec3 at=Paradise.RESCUE.add(0,lift,0);
            if(!level.noCollision(entity,entity.getBoundingBox().move(at.subtract(entity.position()))))continue;
            BlockPos centre=BlockPos.containing(at).below();
            for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)
                level.setBlock(centre.offset(dx,0,dz),Blocks.SMOOTH_QUARTZ.defaultBlockState(),2|16);
            return at;
        }
        return Paradise.ARRIVAL;
    }
    private static void moveTree(Entity entity,Vec3 at) {
        Vec3 delta=at.subtract(entity.position());
        // Passengers retain their relationship and local seat offset, including players in boats.
        for(Entity passenger:entity.getPassengers())moveTree(passenger,passenger.position().add(delta));
        if(entity instanceof ServerPlayer player)player.connection.teleport(at.x,at.y,at.z,player.getYRot(),player.getXRot());
        else entity.teleportTo(at.x,at.y,at.z);
        entity.fallDistance=0;
        entity.hurtMarked=true;
    }
}
