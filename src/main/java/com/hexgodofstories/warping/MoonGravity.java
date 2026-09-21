package com.hexgodofstories.warping;

import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.Map;
import java.util.WeakHashMap;

/** The prison moon is an indestructible analytic surface, shared by the renderer and collision. */
public final class MoonGravity {
    public static final Vec3 CENTER=new Vec3(0,CosmicPhysics.MOON_Y,0);
    private static final Map<Entity,Frame> FRAMES=new WeakHashMap<>();
    private record Frame(Vec3 up,Vec3 forward) {}
    public static boolean active(Entity e) {
        return e!=null&&Destination.from(e.level())==Destination.CRUSHING_REALM&&!e.isSpectator()
            &&!(e instanceof Player p&&p.isCreative()&&p.getAbilities().flying);
    }
    public static Vec3 up(Vec3 feet) {
        Vec3 v=feet.subtract(CENTER);return v.lengthSqr()<1e-8?new Vec3(0,1,0):v.normalize();
    }
    public static double radius(Vec3 normal) {
        return CosmicPhysics.moonRadius(new CosmicPhysics.V(normal.x,normal.y,normal.z));
    }
    /** Parallel transport keeps the controls and camera continuous across both poles. */
    public static Quaternionf rotation(Entity e,Vec3 feet) {
        Vec3 u=up(feet);Frame last=FRAMES.get(e);Vec3 forward;
        if(last==null) {
            Quaternionf q=new Quaternionf().rotationTo(0,1,0,(float)u.x,(float)u.y,(float)u.z);
            Vector3f f=q.transform(new Vector3f(0,0,1));forward=new Vec3(f.x,f.y,f.z);
        } else {
            Quaternionf turn=new Quaternionf().rotationTo((float)last.up.x,(float)last.up.y,(float)last.up.z,(float)u.x,(float)u.y,(float)u.z);
            Vector3f f=turn.transform(new Vector3f((float)last.forward.x,(float)last.forward.y,(float)last.forward.z));
            forward=new Vec3(f.x,f.y,f.z);
        }
        forward=forward.subtract(u.scale(forward.dot(u))).normalize();
        Vec3 right=u.cross(forward).normalize();FRAMES.put(e,new Frame(u,forward));
        return new Quaternionf().setFromNormalized(new org.joml.Matrix3f(
            (float)right.x,(float)right.y,(float)right.z,(float)u.x,(float)u.y,(float)u.z,
            (float)forward.x,(float)forward.y,(float)forward.z));
    }
    public static Vec3 transform(Entity e,Vec3 local) {
        Vector3f v=rotation(e,e.position()).transform(new Vector3f((float)local.x,(float)local.y,(float)local.z));
        return new Vec3(v.x,v.y,v.z);
    }
    public static Vec3 forward(Entity e) {
        rotation(e,e.position());return FRAMES.get(e).forward;
    }
    public static void frame(Entity e,Vec3 f) {
        if(!active(e)||!Double.isFinite(f.lengthSqr())||f.lengthSqr()<.5||f.lengthSqr()>1.5)return;
        Vec3 u=up(e.position()), tangent=f.subtract(u.scale(f.dot(u)));
        if(tangent.lengthSqr()>.5)FRAMES.put(e,new Frame(u,tangent.normalize()));
    }
    public static Vec3 eye(Entity e,float partial) {
        Vec3 feet=new Vec3(net.minecraft.util.Mth.lerp(partial,e.xo,e.getX()),net.minecraft.util.Mth.lerp(partial,e.yo,e.getY()),net.minecraft.util.Mth.lerp(partial,e.zo,e.getZ()));
        return feet.add(up(feet).scale(e.getEyeHeight()));
    }
    public static boolean grounded(Entity e) {
        Vec3 u=up(e.position());return e.position().distanceTo(CENTER)<=radius(u)+.08;
    }
    public static void travel(LivingEntity e,Vec3 input) {
        if(!e.isControlledByLocalInstance())return;
        Vec3 u=up(e.position()),velocity=e.getDeltaMovement();
        double radial=velocity.dot(u);
        Vec3 tangent=velocity.subtract(u.scale(radial)).scale(.50);
        double yaw=Math.toRadians(e.getYRot()), x=input.x,z=input.z;
        double length=Math.sqrt(x*x+z*z);if(length>1){x/=length;z/=length;}
        // About half normal walking speed, still fully steerable. Sprint cannot defeat the prison.
        double speed=.058*(e.isShiftKeyDown()?.45:1);
        Vec3 control=transform(e,new Vec3(x*Math.cos(yaw)-z*Math.sin(yaw),0,z*Math.cos(yaw)+x*Math.sin(yaw))).scale(speed);
        tangent=tangent.add(control);
        // Flight abilities, levitation and elytra cannot replace the moon's inward gravity.
        radial=Math.max(-1.8,Math.min(.34,radial)-CosmicPhysics.MOON_GRAVITY);
        Vec3 next=e.position().add(tangent).add(u.scale(radial));
        Vec3 normal=up(next);double surface=radius(normal)+.025;
        boolean land=next.distanceTo(CENTER)<=surface;
        if(land)next=CENTER.add(normal.scale(surface));
        Vec3 step=next.subtract(e.position());
        e.move(MoverType.SELF,step);
        e.setOnGround(land);e.fallDistance=0;
        e.setDeltaMovement(tangent.subtract(normal.scale(tangent.dot(normal))).add(normal.scale(land?0:radial)));
        e.walkAnimation.update((float)Math.min(1,tangent.length()*5),.4f);
    }
    public static void jump(LivingEntity e) {
        Vec3 u=up(e.position()),v=e.getDeltaMovement();
        e.setDeltaMovement(v.subtract(u.scale(v.dot(u))).add(u.scale(.34)));e.setOnGround(false);e.hasImpulse=true;
    }
    /** Correct packet rounding, teleports into the core, and non-living entities without a travel method. */
    public static void enforce(Entity e) {
        Vec3 u=up(e.position());double r=e.position().distanceTo(CENTER),surface=radius(u)+.025;
        if(r<surface-.08||r>180) {
            Vec3 at=CENTER.add(u.scale(surface));
            if(e instanceof net.minecraft.server.level.ServerPlayer p)p.connection.teleport(at.x,at.y,at.z,p.getYRot(),p.getXRot());
            else e.setPos(at.x,at.y,at.z);
            e.setDeltaMovement(Vec3.ZERO);e.setOnGround(true);
        }
        if(!(e instanceof LivingEntity)) {
            Vec3 v=e.getDeltaMovement(),next=e.position().add(v.scale(.65)).subtract(u.scale(.24));
            Vec3 n=up(next);double s=radius(n)+.08;
            if(next.distanceTo(CENTER)<s){next=CENTER.add(n.scale(s));v=Vec3.ZERO;}
            e.setPos(next.x,next.y,next.z);e.setDeltaMovement(v.scale(.5));e.hurtMarked=true;
        }
        e.fallDistance=0;
    }
    public static void clear(){FRAMES.clear();}
    private MoonGravity() {}
}
