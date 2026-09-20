package com.loki.server;

import com.loki.Loki;
import com.loki.data.*;
import com.loki.network.LokiNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;
import java.util.*;

/**
 * Time Branch Unleashing: the last move of Glorious Purpose, and the only one that needs the mantle.
 *
 * <p>The server owns everything that matters. It decides whether the caster is transformed, how long the
 * charge ran, where they were aiming, how wide the torrent is, what it caught, what was held still and
 * what was erased. Two small packets carry that decision — one when the charge begins, one when it is
 * released — and every client then draws the sphere, the torrent, the lightning and the dissolves from
 * the same numbers. Nothing cosmetic is streamed, so a sixty-block beam costs the network almost nothing.
 *
 * <p>The volume the picture uses and the volume the hits use are the same volume, out of
 * {@link BranchCharge}. A body is never erased outside the visible torrent and never survives inside it.
 *
 * <p>Nothing stops it. The torrent does not collide with the world, is not slowed by it and is not shortened
 * by it: it runs its full hundred blocks through stone, ore, a wall, a vault door or a modded machine alike.
 * Everything it passes through is replaced with {@link com.loki.block.NothingnessBlock} — absolute black,
 * unbreakable, no drops — and every one of those positions is written down first, completely, so that about
 * half a minute later {@link Nothingness} puts the world back exactly as it was, down to a chest's
 * inventory and a sign's text. The destruction is theatre; the record is the real work.
 */
public final class TimeBranch {
    private TimeBranch() {}

    /** A caster with both arms out, planted, compressing the branches. */
    private static final class Cast {
        Vec3 anchor;final long start;
        Cast(Vec3 anchor,long start) {this.anchor=anchor;this.start=start;}
    }
    /** An opened torrent, sweeping forward: what it has already caught and what it has yet to reach. */
    private static final class Torrent {
        final UUID caster;final Vec3 origin,direction;final double length;final float power;
        final long start;final int life;final List<BlockPos> volume;final double[] distances;final long restoreAt;
        final Set<UUID> caught=new HashSet<>();
        int cursor;
        Torrent(UUID caster,Vec3 origin,Vec3 direction,double length,float power,long start,int life,
                List<BlockPos> volume,double[] distances,long restoreAt) {
            this.caster=caster;this.origin=origin;this.direction=direction;this.length=length;
            this.power=power;this.start=start;this.life=life;this.volume=volume;this.distances=distances;
            this.restoreAt=restoreAt;
        }
    }
    private static final Map<UUID,Cast> CASTS=new HashMap<>();
    private static final List<Torrent> TORRENTS=new ArrayList<>();
    /** Bounds on the one-shot work a single release is allowed to do. */
    private static final int MAX_BLOCKS=4200,BLOCKS_PER_TICK=220,MAX_TORRENTS=6;
    /** How far a planted caster may drift before the server puts them back. */
    private static final double DRIFT=.32;
    private static final double AUDIBLE=132;

    public static boolean charging(Player p) {return CASTS.containsKey(p.getUUID());}
    /** True while the caster is planted: the movement lock and the client input lock both read this. */
    public static boolean planted(Player p) {return charging(p);}

    // --------------------------------------------------------------------- charge ---

    /**
     * Begins a charge. Refused outright unless the mantle is worn — no pose, no sphere, no energy spent
     * and no packet sent, so an untransformed press does nothing whatsoever.
     *
     * @return true when the charge actually started.
     */
    public static boolean begin(ServerPlayer p) {
        if(charging(p))return false;
        if(!Transformation.transformed(p)) {
            p.displayClientMessage(Component.literal("The Time Branches answer only the transformed."),true);
            return false;
        }
        if(!p.isAlive()||p.isSpectator()||TemporalEngine.frozen(p)||Erasure.erasing(p))return false;
        Telekinesis.forget(p);
        long now=LokiData.now(p);
        CASTS.put(p.getUUID(),new Cast(p.position(),now));
        LokiData.get(p).putLong("branchStart",now);
        LokiNetwork.animate(p,"unleash");
        sync(p,now,false);
        return true;
    }

    /** Per-tick upkeep for a planted caster: stay put, stay eligible, and never hold past the limit. */
    public static void tick(ServerPlayer p) {
        Cast cast=CASTS.get(p.getUUID());
        if(cast==null)return;
        long now=LokiData.now(p);
        int held=(int)(now-cast.start);
        if(!p.isAlive()||p.isSpectator()||!Transformation.transformed(p)||TemporalEngine.frozen(p)||Erasure.erasing(p)) {
            cancel(p);return;
        }
        // Planted. Both feet stay where the charge began; the head is left free so they can still aim.
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance=0;
        p.hurtMarked=true;
        if(p.position().distanceToSqr(cast.anchor)>DRIFT*DRIFT)
            p.connection.teleport(cast.anchor.x,cast.anchor.y,cast.anchor.z,p.getYRot(),p.getXRot());
        if(held>=BranchCharge.LIMIT)release(p);
    }

    /** The hard limit and the key release both come here. */
    public static void release(ServerPlayer p) {
        Cast cast=CASTS.remove(p.getUUID());
        if(cast==null)return;
        LokiData.get(p).remove("branchStart");
        int held=Math.min(BranchCharge.LIMIT,(int)(LokiData.now(p)-cast.start));
        LokiNetwork.animate(p,"__clear__");
        fire(p,held);
        LokiData.get(p).putLong("cd_"+Ability.TIME_BRANCH.name(),LokiData.now(p)+Ability.TIME_BRANCH.cooldown);
        LokiServer.reward(p,Discipline.PURPOSE,180);
        LokiNetwork.sync(p);
    }

    /** An interrupted charge costs a short recovery and hands the energy back. */
    public static void cancel(ServerPlayer p) {
        if(CASTS.remove(p.getUUID())==null)return;
        LokiData.get(p).remove("branchStart");
        LokiData.energy(p,LokiData.energy(p)+Ability.TIME_BRANCH.cost);
        LokiData.get(p).putLong("cd_"+Ability.TIME_BRANCH.name(),LokiData.now(p)+40);
        LokiNetwork.animate(p,"__clear__");
        sync(p,0,true);
        LokiNetwork.sync(p);
    }

    private static void sync(ServerPlayer p,long start,boolean ended) {
        CompoundTag n=new CompoundTag();
        n.putBoolean("charging",!ended);
        n.putLong("start",start);
        LokiNetwork.near(p.serverLevel(),p.position(),AUDIBLE,new LokiNetwork.Message(LokiNetwork.BRANCH,p.getId(),n));
    }

    // ---------------------------------------------------------------------- release ---

    private static void fire(ServerPlayer p,int held) {
        ServerLevel level=p.serverLevel();
        float power=BranchCharge.power(held);
        Vec3 origin=BranchCharge.focus(p,1);
        Vec3 direction=p.getLookAngle();
        // Its full reach, every time. No raycast, no wall, no shortening: whatever is in the way is in the
        // way and will be taken out of the way.
        double length=BranchCharge.RANGE;
        double erase=BranchCharge.eraseRadius(power);
        List<BlockPos> volume=BeamPath.occupied(level,origin,direction,BranchCharge.SAFE,length,erase,MAX_BLOCKS);
        double[] distances=new double[volume.size()];
        for(int i=0;i<volume.size();i++)distances[i]=axial(origin,direction,Vec3.atCenterOf(volume.get(i)));
        long now=level.getGameTime();
        int life=BranchCharge.life(length,power);
        if(TORRENTS.size()>=MAX_TORRENTS)TORRENTS.remove(0);
        TORRENTS.add(new Torrent(p.getUUID(),origin,direction,length,power,now,life,volume,distances,
            now+life+BranchCharge.RESTORE));

        CompoundTag n=new CompoundTag();
        n.putDouble("x",origin.x);n.putDouble("y",origin.y);n.putDouble("z",origin.z);
        n.putDouble("dx",direction.x);n.putDouble("dy",direction.y);n.putDouble("dz",direction.z);
        n.putDouble("length",length);n.putFloat("power",power);n.putInt("held",held);
        n.putLong("start",now);n.putInt("life",life);
        LokiNetwork.near(level,origin,AUDIBLE+length,new LokiNetwork.Message(LokiNetwork.TORRENT,p.getId(),n));
        // The discharge itself. Everything softer is layered client-side off the same state.
        level.playSound(null,BlockPos.containing(origin),Loki.BRANCH_RELEASE.get(),SoundSource.PLAYERS,1.6f,.72f-power*.14f);
    }

    private static double axial(Vec3 origin,Vec3 direction,Vec3 point) {return point.subtract(origin).dot(direction);}

    // ----------------------------------------------------------------------- sweep ---

    /**
     * Advances every open torrent by one tick. The front moves, bodies it has reached are taken, and soft
     * cover leaves the world as its dissolve finishes — the same schedule the clients are drawing to.
     */
    public static void tickLevel(ServerLevel level) {
        if(TORRENTS.isEmpty())return;
        long now=level.getGameTime();
        Iterator<Torrent> it=TORRENTS.iterator();
        while(it.hasNext()) {
            Torrent t=it.next();
            long age=now-t.start;
            if(age<0||age>t.life){it.remove();continue;}
            ServerPlayer caster=level.getServer().getPlayerList().getPlayer(t.caster);
            if(caster==null||caster.level()!=level){if(caster==null)it.remove();continue;}
            double front=BranchCharge.front(t.length,age);
            catchBodies(caster,t,front);
            carve(level,t,age);
        }
    }

    private static void catchBodies(ServerPlayer caster,Torrent t,double front) {
        if(front<=BranchCharge.SAFE)return;
        double radius=BranchCharge.catchRadius(t.power);
        Vec3 end=t.origin.add(t.direction.scale(front));
        AABB box=new AABB(t.origin,end).inflate(radius);
        for(LivingEntity victim:caster.level().getEntitiesOfClass(LivingEntity.class,box,e->e!=caster&&e.isAlive())) {
            if(t.caught.contains(victim.getUUID()))continue;
            Vec3 centre=victim.getBoundingBox().getCenter();
            double axis=axial(t.origin,t.direction,centre);
            if(axis<BranchCharge.SAFE||axis>front)continue;
            // Measured against the body, not its middle, so a tall creature clipped by the edge counts.
            double perpendicular=Math.sqrt(centre.distanceToSqr(t.origin.add(t.direction.scale(axis))));
            if(perpendicular>radius+Math.max(victim.getBbWidth(),victim.getBbHeight())*.35)continue;
            // Recorded either way. A refusal here is final for this torrent — the caster's own animal, a
            // player who cannot be harmed, or a full register — so the sweep does not retry it every tick.
            Erasure.begin(caster,victim,t.direction,t.power);
            t.caught.add(victim.getUUID());
        }
    }

    /**
     * The world coming apart as the front reaches it, capped per tick so even a hundred-block bore cannot
     * spike the server. Each position is recorded before it is replaced and is owed a restore from that
     * moment, so an interrupted or overlapping cast can still only ever add to the record.
     */
    private static void carve(ServerLevel level,Torrent t,long age) {
        int taken=0;
        while(t.cursor<t.volume.size()&&taken<BLOCKS_PER_TICK) {
            if(BranchCharge.reaches(t.distances[t.cursor])+BranchCharge.DISSOLVE>age)break;
            BlockPos pos=t.volume.get(t.cursor++);
            if(Nothingness.take(level,pos,t.restoreAt))taken++;
        }
    }

    public static void forget(Player p) {CASTS.remove(p.getUUID());}
    public static void reset() {CASTS.clear();TORRENTS.clear();}
}
