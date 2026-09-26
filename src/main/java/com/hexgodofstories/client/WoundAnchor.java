package com.hexgodofstories.client;

import com.hexgodofstories.entity.ThrownDagger;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.*;
import java.util.*;
import java.lang.Math;

/** Server contact in body space, refined against the actual animated model part when available. */
public final class WoundAnchor {
    private WoundAnchor() {}
    private static final Map<UUID,Pin> PINS=new HashMap<>();
    private static final Map<Integer,List<ThrownDagger>> BY_HOST=new HashMap<>();
    private static final Deque<Active> ACTIVE=new ArrayDeque<>();
    private static Matrix4f inverseView;
    private static Vec3 camera=Vec3.ZERO;
    private static Object world;
    /** True while a living body's own model is being emitted (WoundBodyMixin), not its layers. */
    private static boolean body;
    /** Whether that bracket has ever fired; if another mod took the call over, every part counts. */
    private static boolean bodyKnown;
    private record Active(Entity host,float partial,List<ThrownDagger> daggers) {}
    private static final class Pin {
        Object part,candidate;
        Vector3f local,candidateLocal;
        Quaternionf localRotation,candidateRotation,rotation;
        Matrix4f candidateMatrix;
        Vec3 at,hostAt;
        double nearest;
        long updated=-100;
    }

    public static void clear(){PINS.clear();BY_HOST.clear();ACTIVE.clear();inverseView=null;world=null;}
    public static void beginFrame(RenderLevelStageEvent event) {
        var level=Minecraft.getInstance().level;
        if(world!=level){clear();world=level;}
        inverseView=new Matrix4f(event.getPoseStack().last().pose()).invert();
        camera=event.getCamera().getPosition();
        BY_HOST.clear();
        Set<UUID> alive=new HashSet<>();
        if(level!=null)for(Entity e:level.entitiesForRendering())if(e instanceof ThrownDagger dagger
            &&dagger.state()!=ThrownDagger.FLYING&&dagger.state()!=ThrownDagger.IN_BLOCK) {
            if(alive.size()>=512)break;
            alive.add(dagger.getUUID());
            BY_HOST.computeIfAbsent(dagger.state(),id->new ArrayList<>()).add(dagger);
        }
        PINS.keySet().retainAll(alive);
    }

    public static void body(boolean emitting){body=emitting;bodyKnown|=emitting;}
    public static void beginEntity(Entity host,float partial) {
        body=false;
        List<ThrownDagger> daggers=BY_HOST.getOrDefault(host.getId(),List.of());
        ACTIVE.push(new Active(host,partial,daggers));
        for(ThrownDagger dagger:daggers) {
            Pin pin=PINS.computeIfAbsent(dagger.getUUID(),id->new Pin());
            if(host.level().getGameTime()-pin.updated>2)pin.part=null;
            pin.candidate=null;pin.nearest=Double.POSITIVE_INFINITY;
        }
    }
    public static void endEntity() {
        Active active=ACTIVE.pop();
        body=false;
        BeamWounds.endEntity(active.host());
        for(ThrownDagger dagger:active.daggers()) {
            Pin pin=PINS.get(dagger.getUUID());
            if(pin.candidate==null)continue;
            pin.part=pin.candidate;pin.local=pin.candidateLocal;pin.localRotation=pin.candidateRotation;
            update(pin,pin.candidateMatrix,active);
        }
    }

    /** Invoked only when an actual ModelPart emits its posed geometry, including walk/attack/scale. */
    public static void capturePart(Object part,PoseStack.Pose pose,List<ModelPart.Cube> cubes) {
        if(!ACTIVE.isEmpty())BeamWounds.capture(ACTIVE.peek().host(),ACTIVE.peek().partial(),part,pose,cubes,body||!bodyKnown);
        if(ACTIVE.isEmpty()||ACTIVE.peek().daggers().isEmpty()||inverseView==null||cubes.isEmpty())return;
        Active active=ACTIVE.peek();
        Matrix4f matrix=new Matrix4f(inverseView).mul(pose.pose());
        if(!Float.isFinite(matrix.determinant())||Math.abs(matrix.determinant())<1e-8)return;
        Matrix4f inverse=null;
        for(ThrownDagger dagger:active.daggers()) {
            Pin pin=PINS.get(dagger.getUUID());
            if(pin.part!=null) {
                if(pin.part==part)update(pin,matrix,active);
                continue;
            }
            if(inverse==null)inverse=new Matrix4f(matrix).invert();
            Vec3 contact=rigidWorld(dagger,active.host(),active.partial()).subtract(camera);
            Vector3f local=inverse.transformPosition(new Vector3f((float)contact.x,(float)contact.y,(float)contact.z));
            for(ModelPart.Cube cube:cubes) {
                Vector3f nearest=new Vector3f(Mth.clamp(local.x,cube.minX/16,cube.maxX/16),
                    Mth.clamp(local.y,cube.minY/16,cube.maxY/16),Mth.clamp(local.z,cube.minZ/16,cube.maxZ/16));
                matrix.transformPosition(nearest);
                double distance=contact.distanceToSqr(new Vec3(nearest.x,nearest.y,nearest.z));
                if(distance>=pin.nearest)continue;
                pin.nearest=distance;pin.candidate=part;pin.candidateLocal=new Vector3f(local);
                pin.candidateMatrix=matrix;
                pin.candidateRotation=matrix.getUnnormalizedRotation(new Quaternionf()).invert()
                    .mul(rigidRotation(dagger,active.host(),active.partial()));
            }
        }
    }
    private static void update(Pin pin,Matrix4f matrix,Active active) {
        Vector3f at=matrix.transformPosition(new Vector3f(pin.local));
        pin.at=camera.add(at.x,at.y,at.z);pin.hostAt=lerpPosition(active.host(),active.partial());
        pin.rotation=matrix.getUnnormalizedRotation(new Quaternionf()).mul(pin.localRotation);
        pin.updated=active.host().level().getGameTime();
    }
    private static Pin fresh(ThrownDagger dagger,Entity host) {
        Pin pin=PINS.get(dagger.getUUID());
        return pin!=null&&pin.at!=null&&host.level().getGameTime()-pin.updated<=2?pin:null;
    }
    public static Vec3 world(ThrownDagger dagger,Entity host,float partial) {
        Pin pin=fresh(dagger,host);
        return pin==null?rigidWorld(dagger,host,partial):pin.at.add(lerpPosition(host,partial).subtract(pin.hostAt));
    }
    public static Quaternionf rotation(ThrownDagger dagger,Entity host,float partial) {
        Pin pin=fresh(dagger,host);
        return pin==null?rigidRotation(dagger,host,partial):new Quaternionf(pin.rotation);
    }
    private static Vec3 rigidWorld(ThrownDagger dagger,Entity host,float partial) {
        Vec3 unit=dagger.offset();
        Vec3 local=unit.multiply(Math.max(.1,host.getBbWidth()),Math.max(.1,host.getBbHeight()),Math.max(.1,host.getBbWidth()));
        return lerpPosition(host,partial).add(local.yRot(-bodyYaw(host,partial)*Mth.DEG_TO_RAD));
    }
    private static Quaternionf rigidRotation(ThrownDagger dagger,Entity host,float partial) {
        return new Quaternionf().rotationY((dagger.entryYaw()-bodyYaw(host,partial)-90)*Mth.DEG_TO_RAD)
            .rotateZ(dagger.entryPitch()*Mth.DEG_TO_RAD).rotateX(dagger.roll()*Mth.DEG_TO_RAD)
            .rotateZ(-Mth.HALF_PI);
    }
    public static Vec3 lerpPosition(Entity e,float partial) {
        return new Vec3(Mth.lerp(partial,e.xo,e.getX()),Mth.lerp(partial,e.yo,e.getY()),Mth.lerp(partial,e.zo,e.getZ()));
    }
    public static float bodyYaw(Entity host,float partial) {
        return host instanceof LivingEntity living?Mth.rotLerp(partial,living.yBodyRotO,living.yBodyRot)
            :Mth.rotLerp(partial,host.yRotO,host.getYRot());
    }
}
