package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The unseen grasp.
 *
 * <p>Two things were wrong with the previous hand and both are fixed here. It barely worked, because a
 * damped spring fighting a creature's own movement and its client interpolation loses: a held body lagged
 * behind the aim, fought the correction and snapped back. And it could hold almost nothing, because the
 * mass limit started below the weight of most things worth picking up.
 *
 * <p>So a creature is now <em>moved</em> rather than pushed. Each tick it is stepped toward its goal with
 * a collision-aware move, which lands exactly where it is asked to and lets the client's own
 * interpolation smooth it, and it is turned as it goes so the hold reads as control rather than as
 * something stuck to the crosshair. A held player is the one exception — their client owns their body, so
 * they are driven by velocity with their movement input taken away, which removes the fight that caused
 * the rubber-banding rather than papering over it with teleports.
 *
 * <p>The limit is now generous enough to be a spell. Ownership, capacity, mass and every release rule
 * still live here; clients only draw the grasp.
 */
public final class Telekinesis {
    private static final class Held {
        final Entity entity;final boolean gravity,noAi;final long end;
        double spin;
        Held(Entity entity,boolean gravity,boolean noAi,long end) {
            this.entity=entity;this.gravity=gravity;this.noAi=noAi;this.end=end;
        }
    }
    private static final class Grip {
        final List<Held> held=new ArrayList<>();
        Vec3 aim;double distance=4;long animated;
        Grip(Vec3 aim) {this.aim=aim;}
    }
    private record Slam(Entity entity,UUID owner,double power,long expires) {}
    private static final Map<UUID,Grip> GRIPS=new HashMap<>();
    private static final List<Slam> SLAMS=new ArrayList<>();
    private static final double MIN_DISTANCE=1.9,LIFT=.42;
    /** The furthest a body is stepped in one tick, so a long reel-in is fast but never a teleport. */
    private static final double STEP=1.15;

    public static boolean holding(Player p) {return GRIPS.containsKey(p.getUUID());}
    public static int count(Player p) {Grip g=GRIPS.get(p.getUUID());return g==null?0:g.held.size();}
    public static boolean heldBySomeone(Entity e) {
        for(Grip g:GRIPS.values())for(Held h:g.held)if(h.entity==e)return true;
        return false;
    }

    private static int capacity(ServerPlayer p) {return 1+HexData.mastery(p,Discipline.SORCERY)/160;}
    private static double maxDistance(ServerPlayer p) {return 6+HexData.mastery(p,Discipline.SORCERY)*.016;}
    /**
     * What the hand can lift. Deliberately generous from the moment the spell unlocks — an unseen hand
     * that cannot pick up a cow is not a spell, it is a bug — and it still grows, so the largest
     * creatures stay something to work up to.
     */
    private static double massLimit(ServerPlayer p) {return 4+HexData.mastery(p,Discipline.SORCERY)*.12;}

    /** @return true when the target was taken, so the caster only pays the cost on a real grab. */
    public static boolean grab(ServerPlayer p,Entity t) {
        if(t==null||!HexServer.validTarget(p,t)||t.isPassenger()||t.isVehicle()||TemporalEngine.frozen(t)||heldBySomeone(t))return false;
        if(Erasure.erasing(t))return false;
        double mass=t.getBbWidth()*t.getBbWidth()*t.getBbHeight();
        if(mass>massLimit(p)) {
            p.displayClientMessage(Component.literal("This creature is beyond your reach — for now."),true);
            return false;
        }
        Grip grip=GRIPS.computeIfAbsent(p.getUUID(),k->new Grip(p.getLookAngle()));
        if(grip.held.size()>=capacity(p)) {
            p.displayClientMessage(Component.literal("Your grip is already full."),true);
            return false;
        }
        long life=HexData.now(p)+(t instanceof Player?70:300);
        Held held=new Held(t,t.isNoGravity(),t instanceof Mob m&&m.isNoAi(),life);
        held.spin=t.getYRot();
        grip.held.add(held);
        grip.distance=Math.max(MIN_DISTANCE,Math.min(maxDistance(p),p.distanceTo(t)));
        t.setNoGravity(true);t.fallDistance=0;
        if(t instanceof Mob mob){mob.setNoAi(true);mob.getNavigation().stop();mob.setTarget(null);}
        if(grip.held.size()==1)HexNetwork.animate(p,"telekinesis");
        p.level().playSound(null,t.blockPosition(),HexGodOfStories.GRIP_HOLD.get(),SoundSource.PLAYERS,.6f,1.25f);
        sync(p,grip);
        return true;
    }

    /** Mouse wheel while gripping pushes the held mass away or reels it in. */
    public static void adjust(ServerPlayer p,int steps) {
        Grip grip=GRIPS.get(p.getUUID());
        if(grip==null)return;
        grip.distance=Math.max(MIN_DISTANCE,Math.min(maxDistance(p),grip.distance+steps*.6));
        sync(p,grip);
    }

    public static void tick(ServerPlayer p) {
        Grip grip=GRIPS.get(p.getUUID());
        if(grip==null)return;
        long now=HexData.now(p);
        if(TemporalEngine.frozen(p)||!p.isAlive()||p.isSpectator()||TimeBranch.charging(p)){release(p,false);return;}
        grip.held.removeIf(h->{
            boolean gone=!h.entity.isAlive()||h.entity.isRemoved()||h.entity.level()!=p.level()
                ||h.end<now||p.distanceToSqr(h.entity)>2304||Erasure.erasing(h.entity);
            if(gone)restore(h);
            return gone;
        });
        if(grip.held.isEmpty()){release(p,false);return;}
        Vec3 look=p.getLookAngle();
        // The aim trails the head a little, so the held mass swings rather than snapping with the mouse.
        grip.aim=grip.aim.scale(.70).add(look.scale(.30)).normalize();
        Vec3 anchor=p.getEyePosition().add(grip.aim.scale(grip.distance));
        Vec3 side=BranchSide.of(grip.aim);
        Vec3 up=side.cross(grip.aim).normalize();
        int n=grip.held.size();
        double spread=n>1?.85+.24*n:0;
        for(int i=0;i<n;i++) {
            Held h=grip.held.get(i);
            Entity e=h.entity;
            double angle=i*Math.PI*2/n+now*.022;
            Vec3 goal=anchor.add(side.scale(Math.cos(angle)*spread)).add(up.scale(Math.sin(angle)*spread));
            e.setNoGravity(true);
            e.fallDistance=0;
            if(e instanceof ServerPlayer target) {
                // Their own client owns their body. Drive it by velocity and take their movement input
                // away on the client instead of correcting them, which is what used to rubber-band.
                Vec3 centre=e.position().add(0,e.getBbHeight()*.5,0);
                Vec3 delta=goal.subtract(centre);
                Vec3 velocity=delta.scale(.38);
                double speed=velocity.length();
                if(speed>1.5)velocity=velocity.scale(1.5/speed);
                target.setDeltaMovement(velocity);
                target.hurtMarked=true;
                target.connection.send(new ClientboundSetEntityMotionPacket(target));
                target.resetFallDistance();
                continue;
            }
            // Everything else is stepped to where it was asked to be, respecting collision so a body is
            // shoved against a wall rather than through it.
            Vec3 centre=e.position().add(0,e.getBbHeight()*.5,0);
            Vec3 delta=goal.subtract(centre);
            double reach=delta.length();
            if(reach>STEP)delta=delta.scale(STEP/reach);
            e.setDeltaMovement(Vec3.ZERO);
            e.move(MoverType.SELF,delta);
            e.hurtMarked=true;
            // Turned as it is carried: the hold reads as control rather than as something glued on.
            h.spin+=4.5;
            e.setYRot((float)h.spin);
            e.setXRot((float)(Math.sin(now*.09+i)*14));
            if(e instanceof LivingEntity living) {
                living.setYBodyRot((float)h.spin);
                living.setYHeadRot((float)h.spin);
                living.yRotO=living.getYRot();
            }
        }
        if(now-grip.animated>40){grip.animated=now;HexNetwork.animate(p,"telekinesis");}
        if(now%10==0)sync(p,grip);
        if(now%14==0)p.level().playSound(null,p.blockPosition(),HexGodOfStories.GRIP_HOLD.get(),SoundSource.PLAYERS,.24f,1.5f);
    }

    public static void release(ServerPlayer p,boolean thrown) {
        Grip grip=GRIPS.remove(p.getUUID());
        if(grip==null)return;
        double power=2.6+HexData.mastery(p,Discipline.SORCERY)*.006;
        for(Held h:grip.held) {
            restore(h);
            if(thrown) {
                Vec3 shove=grip.aim.scale(power).add(0,LIFT,0);
                h.entity.setDeltaMovement(shove);
                h.entity.hurtMarked=true;
                if(h.entity instanceof ServerPlayer target)target.connection.send(new ClientboundSetEntityMotionPacket(target));
                if(SLAMS.size()<128)SLAMS.add(new Slam(h.entity,p.getUUID(),power,HexData.now(p)+50));
                HexNetwork.fx(h.entity,"hurl");
            }
        }
        HexData.get(p).remove("grip");
        HexNetwork.animate(p,thrown?"push":"__clear__");
        CompoundTag n=new CompoundTag();n.putInt("count",0);
        HexNetwork.tracking(p,new HexNetwork.Message(HexNetwork.GRIP,p.getId(),n));
        HexNetwork.sync(p);
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
                living.hurt(owner.damageSources().playerAttack(owner),(float)Math.min(13,3.5+slam.power*2.4));
                HexNetwork.fx(living,"impact");
                HexServer.reward(owner,Discipline.SORCERY,45);
            }
            it.remove();
        }
    }

    private static void restore(Held h) {
        h.entity.setNoGravity(h.gravity);
        if(h.entity instanceof Mob m)m.setNoAi(h.noAi);
        h.entity.fallDistance=0;
        h.entity.setXRot(0);
    }
    private static void sync(ServerPlayer p,Grip grip) {
        CompoundTag n=new CompoundTag();
        n.putInt("count",grip.held.size());
        int[] ids=new int[grip.held.size()];
        for(int i=0;i<ids.length;i++)ids[i]=grip.held.get(i).entity.getId();
        n.putIntArray("targets",ids);
        n.putDouble("distance",grip.distance);
        HexData.get(p).putInt("grip",grip.held.size());
        HexNetwork.tracking(p,new HexNetwork.Message(HexNetwork.GRIP,p.getId(),n));
    }
    /** Drops everything without a throw, and tells viewers to stop drawing the grasp. */
    public static void forget(ServerPlayer p) {
        Grip grip=GRIPS.remove(p.getUUID());
        if(grip==null)return;
        grip.held.forEach(Telekinesis::restore);
        HexData.get(p).remove("grip");
        CompoundTag n=new CompoundTag();n.putInt("count",0);
        HexNetwork.tracking(p,new HexNetwork.Message(HexNetwork.GRIP,p.getId(),n));
    }
    public static void reset() {GRIPS.values().forEach(g->g.held.forEach(Telekinesis::restore));GRIPS.clear();SLAMS.clear();}

    /** A side vector for the carry formation; kept here so the server needs nothing from the client. */
    private static final class BranchSide {
        static Vec3 of(Vec3 axis) {
            Vec3 side=axis.cross(new Vec3(0,1,0));
            if(side.lengthSqr()<1e-6)side=axis.cross(new Vec3(1,0,0));
            if(side.lengthSqr()<1e-6)return new Vec3(1,0,0);
            return side.normalize();
        }
    }
}
