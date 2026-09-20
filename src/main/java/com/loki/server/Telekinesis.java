package com.loki.server;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Held targets ride a damped spring toward a smoothed aim point rather than being teleported each tick,
 * so a held player keeps normal client interpolation and never rubber-bands. Ownership, mass limits and
 * release rules all live on the server; clients only draw the strands.
 */
public final class Telekinesis {
    private static final class Held {
        final Entity entity;final boolean gravity,noAi;final long end;
        Held(Entity entity,boolean gravity,boolean noAi,long end) {this.entity=entity;this.gravity=gravity;this.noAi=noAi;this.end=end;}
    }
    private static final class Grip {
        final List<Held> held=new ArrayList<>();
        Vec3 aim;double distance=4;long animated;
        Grip(Vec3 aim) {this.aim=aim;}
    }
    private record Slam(Entity entity,UUID owner,double power,long expires) {}
    private static final Map<UUID,Grip> GRIPS=new HashMap<>();
    private static final List<Slam> SLAMS=new ArrayList<>();
    private static final double MIN_DISTANCE=1.8,LIFT=.45;

    public static boolean holding(Player p) {return GRIPS.containsKey(p.getUUID());}
    public static int count(Player p) {Grip g=GRIPS.get(p.getUUID());return g==null?0:g.held.size();}
    public static boolean heldBySomeone(Entity e) {for(Grip g:GRIPS.values())for(Held h:g.held)if(h.entity==e)return true;return false;}

    private static int capacity(ServerPlayer p) {return 1+LokiData.mastery(p,Discipline.SORCERY)/250;}
    private static double maxDistance(ServerPlayer p) {return 5+LokiData.mastery(p,Discipline.SORCERY)*.011;}
    private static double massLimit(ServerPlayer p) {return 2+LokiData.mastery(p,Discipline.SORCERY)*.035;}

    /** @return true when the target was taken, so the caster only pays the cost on a real grab. */
    public static boolean grab(ServerPlayer p,Entity t) {
        if(t==null||!LokiServer.validTarget(p,t)||t.isPassenger()||t.isVehicle()||TemporalEngine.frozen(t)||heldBySomeone(t))return false;
        double mass=t.getBbWidth()*t.getBbWidth()*t.getBbHeight();
        if(mass>massLimit(p)){p.displayClientMessage(net.minecraft.network.chat.Component.literal("This creature exceeds your control."),true);return false;}
        Grip grip=GRIPS.computeIfAbsent(p.getUUID(),k->new Grip(p.getLookAngle()));
        if(grip.held.size()>=capacity(p)){p.displayClientMessage(net.minecraft.network.chat.Component.literal("Your grip is already full."),true);return false;}
        long life=LokiData.now(p)+(t instanceof Player?60:240);
        grip.held.add(new Held(t,t.isNoGravity(),t instanceof Mob m&&m.isNoAi(),life));
        grip.distance=Math.max(MIN_DISTANCE,Math.min(maxDistance(p),p.distanceTo(t)));
        t.setNoGravity(true);t.fallDistance=0;
        if(t instanceof Mob mob){mob.setNoAi(true);mob.getNavigation().stop();}
        if(grip.held.size()==1)LokiNetwork.animate(p,"telekinesis");
        sync(p,grip);
        return true;
    }

    /** Mouse wheel while gripping pushes the held mass away or reels it in. */
    public static void adjust(ServerPlayer p,int steps) {
        Grip grip=GRIPS.get(p.getUUID());if(grip==null)return;
        grip.distance=Math.max(MIN_DISTANCE,Math.min(maxDistance(p),grip.distance+steps*.55));
    }

    public static void tick(ServerPlayer p) {
        Grip grip=GRIPS.get(p.getUUID());
        if(grip==null)return;
        long now=LokiData.now(p);
        if(TemporalEngine.frozen(p)||!p.isAlive()||p.isSpectator()){release(p,false);return;}
        grip.held.removeIf(h->{
            boolean gone=!h.entity.isAlive()||h.entity.isRemoved()||h.entity.level()!=p.level()||h.end<now||p.distanceToSqr(h.entity)>1024;
            if(gone)restore(h);
            return gone;
        });
        if(grip.held.isEmpty()){release(p,false);return;}
        Vec3 look=p.getLookAngle();
        grip.aim=grip.aim.scale(.72).add(look.scale(.28)).normalize();
        Vec3 anchor=p.getEyePosition().add(grip.aim.scale(grip.distance));
        Vec3 side=grip.aim.cross(new Vec3(0,1,0));
        if(side.lengthSqr()<1e-4)side=new Vec3(1,0,0);
        side=side.normalize();
        Vec3 up=side.cross(grip.aim).normalize();
        int n=grip.held.size();
        double spread=n>1?.75+.22*n:0;
        for(int i=0;i<n;i++) {
            Held h=grip.held.get(i);Entity e=h.entity;
            double angle=i*Math.PI*2/n+now*.02;
            Vec3 goal=anchor.add(side.scale(Math.cos(angle)*spread)).add(up.scale(Math.sin(angle)*spread));
            Vec3 centre=e.position().add(0,e.getBbHeight()*.5,0);
            Vec3 delta=goal.subtract(centre);
            double reach=delta.length();
            Vec3 velocity=e.getDeltaMovement().scale(.52).add(delta.scale(reach>4?.22:.34));
            double speed=velocity.length();
            if(speed>1.35)velocity=velocity.scale(1.35/speed);
            e.setNoGravity(true);e.fallDistance=0;e.setDeltaMovement(velocity);e.hurtMarked=true;
            if(e instanceof ServerPlayer target) {
                target.connection.send(new ClientboundSetEntityMotionPacket(target));
                target.resetFallDistance();
                // Only correct a target that has genuinely escaped, so ordinary struggling stays smooth.
                if(reach>3.5)target.connection.teleport(goal.x,goal.y-e.getBbHeight()*.5,goal.z,target.getYRot(),target.getXRot());
            }
        }
        if(now-grip.animated>40){grip.animated=now;LokiNetwork.animate(p,"telekinesis");}
        if(now%10==0)sync(p,grip);
    }

    public static void release(ServerPlayer p,boolean thrown) {
        Grip grip=GRIPS.remove(p.getUUID());
        if(grip==null)return;
        double power=2+LokiData.mastery(p,Discipline.SORCERY)*.004;
        for(Held h:grip.held) {
            restore(h);
            if(thrown) {
                Vec3 shove=grip.aim.scale(power).add(0,LIFT,0);
                h.entity.setDeltaMovement(shove);h.entity.hurtMarked=true;
                if(h.entity instanceof ServerPlayer target)target.connection.send(new ClientboundSetEntityMotionPacket(target));
                if(SLAMS.size()<128)SLAMS.add(new Slam(h.entity,p.getUUID(),power,LokiData.now(p)+40));
                LokiNetwork.fx(h.entity,"hurl");
            }
        }
        LokiData.get(p).remove("grip");
        LokiNetwork.animate(p,thrown?"push":"__clear__");
        CompoundTag n=new CompoundTag();n.putInt("count",0);LokiNetwork.tracking(p,new LokiNetwork.Message(LokiNetwork.GRIP,p.getId(),n));
        LokiNetwork.sync(p);
    }

    /** A hurled body that meets a wall takes the impact it was carrying. */
    public static void tickSlams(net.minecraft.server.level.ServerLevel level) {
        long now=level.getGameTime();
        Iterator<Slam> it=SLAMS.iterator();
        while(it.hasNext()) {
            Slam slam=it.next();
            if(slam.entity.level()!=level)continue;
            if(now>slam.expires||!slam.entity.isAlive()){it.remove();continue;}
            if(!(slam.entity.horizontalCollision||slam.entity.verticalCollision&&slam.entity.getDeltaMovement().y<-.35))continue;
            ServerPlayer owner=level.getServer().getPlayerList().getPlayer(slam.owner);
            if(owner!=null&&slam.entity instanceof LivingEntity living) {
                living.hurt(owner.damageSources().playerAttack(owner),(float)Math.min(11,3+slam.power*2.2));
                LokiNetwork.fx(living,"impact");
                LokiServer.reward(owner,Discipline.SORCERY,45);
            }
            it.remove();
        }
    }

    private static void restore(Held h) {
        h.entity.setNoGravity(h.gravity);
        if(h.entity instanceof Mob m)m.setNoAi(h.noAi);
        h.entity.fallDistance=0;
    }
    private static void sync(ServerPlayer p,Grip grip) {
        CompoundTag n=new CompoundTag();n.putInt("count",grip.held.size());
        int[] ids=new int[grip.held.size()];
        for(int i=0;i<ids.length;i++)ids[i]=grip.held.get(i).entity.getId();
        n.putIntArray("targets",ids);n.putDouble("distance",grip.distance);
        LokiData.get(p).putInt("grip",grip.held.size());
        LokiNetwork.tracking(p,new LokiNetwork.Message(LokiNetwork.GRIP,p.getId(),n));
    }
    public static void forget(ServerPlayer p) {Grip grip=GRIPS.remove(p.getUUID());if(grip!=null)grip.held.forEach(Telekinesis::restore);}
    public static void reset() {GRIPS.values().forEach(g->g.held.forEach(Telekinesis::restore));GRIPS.clear();SLAMS.clear();}
}
