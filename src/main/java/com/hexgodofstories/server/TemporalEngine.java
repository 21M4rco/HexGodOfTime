package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.item.*;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.phys.*;
import java.util.*;

/**
 * Local temporal suspension. Each field owns its contribution, so removing one caster never releases
 * another caster's hold, and nothing here touches the server tick rate.
 *
 * <p>Slowed time is a rate, not a stutter. Earlier builds withheld an entity's tick on most ticks,
 * which is exactly what produced the teleporting: a body would stand perfectly still for four ticks
 * and then jump a full step. Everything now ticks every tick and instead has its rate of change
 * scaled — movement and attack speed through attributes, momentum through a single proportional
 * correction applied only when the rate itself changes, and gravity through a counter-force that
 * preserves the shape of an arc while stretching it out. The result interpolates on the client the
 * way ordinary motion does, because as far as the client is concerned it is ordinary motion.
 *
 * <p>A full stop still ends in a genuine hold, but it decelerates into it over {@link #RAMP} ticks
 * and accelerates back out the same way, and harm dealt to a suspended body is banked until its
 * moment resumes.
 */
public final class TemporalEngine {
    public record Field(UUID owner,ServerLevel level,Vec3 center,double radius,long started,long expires,boolean stop,UUID target,UUID exempt) {}
    public record Frozen(Entity entity,Vec3 position,Vec3 velocity,float yaw,float pitch,long expires) {}
    private record Banked(float damage,UUID attacker) {}

    private static final List<Field> FIELDS=new ArrayList<>();
    /** Server-side windup; no field or energy drain exists before the downward swing. */
    private record Windup(ServerLevel level,long impact) {}
    private static final Map<UUID,Windup> WINDUPS=new HashMap<>();
    private static final Map<UUID,Frozen> FROZEN=new HashMap<>();
    private static final Map<UUID,Long> PLAYER_GRACE=new HashMap<>();
    /** Entities currently running at a reduced rate, with the rate that has actually been applied. */
    private static final Map<UUID,Entity> SLOWED=new HashMap<>();
    private static final Map<UUID,Double> APPLIED=new HashMap<>();
    /** Bodies spinning back up after a hold released. */
    private static final Map<UUID,Long> RECOVERING=new HashMap<>();
    private static final Map<UUID,Banked> BANKED=new HashMap<>();
    /** Body, head and pitch angles captured before a dilated creature ticks, so turning can be slowed too. */
    private static final Map<UUID,float[]> ROTATION=new HashMap<>();
    private static final UUID DILATION_SPEED=UUID.fromString("65b293d4-1384-446d-902c-7a261ca31cf2");
    private static final UUID DILATION_ATTACK=UUID.fromString("0c6c27e9-1f7d-4c58-9d0a-5b2f6ba0a4c1");
    private static final UUID DILATION_FLIGHT=UUID.fromString("a7d1f3b2-90c5-4a8e-bd41-2f9c8e1d7a30");
    private static final int MAX_FIELDS=64,MAX_ENTITIES_PER_FIELD=192;
    public static final int STOP_RADIUS=10,STOP_EXPANSION=12,STOP_WINDUP=20;
    /** Ticks spent decelerating into, and accelerating out of, a full stop. */
    public static final int RAMP=7;
    /** How much of normal time a dilated body experiences. */
    private static final double DILATION=.32;
    /** The rate a body resumes at the instant a hold lets go, before the ramp brings it back to one. */
    private static final double RESUME_RATE=.06;
    private static final float MAX_BANKED=60;

    public static boolean frozen(Entity e) {return FROZEN.containsKey(e.getUUID());}
    public static Vec3 savedVelocity(Entity e) {
        Frozen frozen=FROZEN.get(e.getUUID());
        return frozen!=null&&frozen.entity==e?frozen.velocity:null;
    }
    public static boolean slowed(Entity e) {return SLOWED.containsKey(e.getUUID());}
    public static double rateOf(Entity e) {return APPLIED.getOrDefault(e.getUUID(),1.0);}
    public static boolean owns(ServerPlayer p) {return WINDUPS.containsKey(p.getUUID())||FIELDS.stream().anyMatch(f->f.owner.equals(p.getUUID()));}
    public static boolean sustaining(ServerPlayer p) {
        return FIELDS.stream().anyMatch(f->f.owner.equals(p.getUUID())&&f.stop&&f.target==null&&f.expires==Long.MAX_VALUE);
    }
    /** Only a true hold withholds a tick; a slowed body keeps ticking, it just changes more slowly. */
    public static boolean skipTick(Entity e) {
        if(frozen(e))return true;
        if(!(e.level() instanceof ServerLevel level))return false;
        long now=level.getGameTime();
        for(Field f:FIELDS) {
            if(f.level!=level||!f.stop||f.target!=null||f.owner.equals(e.getUUID())||f.exempt!=null&&f.exempt.equals(e.getUUID()))continue;
            if(now<f.started||!eligible(e,f,radius(f,now)))continue;
            Frozen s=new Frozen(e,e.position(),e.getDeltaMovement(),e.getYRot(),e.getXRot(),Long.MAX_VALUE);
            FROZEN.put(e.getUUID(),s);hold(e,s,now);sync(s,true);
            return true;
        }
        return false;
    }

    public static boolean beginStop(ServerPlayer p) {
        if(owns(p)||FIELDS.size()>=MAX_FIELDS||p.isPassenger()||HexData.energy(p)<5)return false;
        WINDUPS.put(p.getUUID(),new Windup(p.serverLevel(),p.level().getGameTime()+STOP_WINDUP));
        HexData.get(p).putLong("stopWindup",p.level().getGameTime());
        HexNetwork.animate(p,"time_stop");
        p.level().playSound(null,p.blockPosition(),HexGodOfStories.STOP_CHARGE.get(),SoundSource.PLAYERS,1.1f,1f);
        return true;
    }

    public static boolean field(ServerPlayer p,boolean stop,Entity target,int ticks) {
        if(FIELDS.size()>=MAX_FIELDS)return false;
        if(stop&&target==null&&FIELDS.stream().anyMatch(f->f.owner.equals(p.getUUID())&&f.stop&&f.target==null))return false;
        if(p.isPassenger()||target!=null&&target.isPassenger())return false;
        UUID ally=HexData.get(p).hasUUID("ally")?HexData.get(p).getUUID("ally"):null;
        long now=p.level().getGameTime();
        double radius=stop&&target==null?STOP_RADIUS:HexData.get(p).getBoolean("ascended")?18:12;
        long expires=stop&&target==null?Long.MAX_VALUE:now+ticks;
        FIELDS.add(new Field(p.getUUID(),p.serverLevel(),p.position(),radius,now,expires,stop,target==null?null:target.getUUID(),ally));
        broadcastField(p,p.position(),radius,stop,now,expires,target==null);
        HexNetwork.fx(p,stop?"stop":"dilate");
        return true;
    }
    public static void exempt(ServerPlayer p,Entity ally) {
        HexData.get(p).putUUID("ally",ally.getUUID());
        FIELDS.replaceAll(f->f.owner.equals(p.getUUID())?new Field(f.owner,f.level,f.center,f.radius,f.started,f.expires,f.stop,f.target,ally.getUUID()):f);
    }
    public static void clear(ServerPlayer p) {
        boolean winding=WINDUPS.remove(p.getUUID())!=null;
        HexData.get(p).remove("stopWindup");
        if(winding)HexNetwork.animate(p,"__clear__");
        boolean had=FIELDS.removeIf(f->f.owner.equals(p.getUUID()));
        if(had) {
            CompoundTag n=new CompoundTag();n.putBoolean("active",false);
            HexNetwork.tracking(p,new HexNetwork.Message(HexNetwork.FIELD,p.getId(),n));
            HexNetwork.fx(p,"resume");
            p.level().playSound(null,p.blockPosition(),HexGodOfStories.RESUME.get(),SoundSource.PLAYERS,1.1f,1f);
        }
        if(winding)HexNetwork.sync(p);
        if(had)tick(p.serverLevel());
    }

    /** Harm aimed at a suspended body is held back and delivered the instant its moment resumes. */
    public static boolean bank(Entity victim,float amount,Entity attacker) {
        if(!frozen(victim))return false;
        Banked existing=BANKED.get(victim.getUUID());
        float total=Math.min(MAX_BANKED,(existing==null?0:existing.damage)+amount);
        BANKED.put(victim.getUUID(),new Banked(total,attacker==null?(existing==null?null:existing.attacker):attacker.getUUID()));
        return true;
    }

    private static boolean ticking;

    public static void tick(ServerLevel level) {
        if(ticking)return;
        ticking=true;
        try {run(level);} finally {ticking=false;}
    }

    /** Called only for ticking block entities; a small fixed list, never a world scan. */
    public static boolean stopped(ServerLevel level,net.minecraft.core.BlockPos pos) {
        long now=level.getGameTime();
        for(Field field:FIELDS) {
            if(field.level!=level||!field.stop||field.target!=null||now<field.started)continue;
            double radius=radius(field,now);
            if(pos.distToCenterSqr(field.center.x,field.center.y,field.center.z)<=radius*radius)return true;
        }
        return false;
    }

    private static void run(ServerLevel level) {
        long now=level.getGameTime();
        Iterator<Map.Entry<UUID,Windup>> winds=WINDUPS.entrySet().iterator();
        while(winds.hasNext()) {
            var entry=winds.next();Windup wind=entry.getValue();
            if(wind.level!=level)continue;
            ServerPlayer caster=level.getServer().getPlayerList().getPlayer(entry.getKey());
            if(caster==null||caster.level()!=level||!caster.isAlive()||!HexData.access(caster)||HexData.energy(caster)<5){
                winds.remove();
                if(caster!=null){HexData.get(caster).remove("stopWindup");HexNetwork.sync(caster);}
                continue;
            }
            if(now<wind.impact)continue;
            winds.remove();
            HexData.get(caster).remove("stopWindup");
            if(field(caster,true,null,0)) {
                level.playSound(null,caster.blockPosition(),HexGodOfStories.STOP.get(),SoundSource.PLAYERS,1.3f,1f);
                HexNetwork.sync(caster);
            }
        }
        FIELDS.removeIf(f->{
            if(f.level!=level)return false;
            ServerPlayer owner=level.getServer().getPlayerList().getPlayer(f.owner);
            boolean sustained=f.stop&&f.target==null&&f.expires==Long.MAX_VALUE;
            boolean expired=f.expires<=now||owner==null||owner.level()!=level||!owner.isAlive()
                ||sustained&&(HexData.energy(owner)<5||now-f.started>=20&&
                    (now-f.started)%20==0&&!HexData.spend(owner,5));
            if(expired&&owner!=null) {
                CompoundTag n=new CompoundTag();n.putBoolean("active",false);
                HexNetwork.tracking(owner,new HexNetwork.Message(HexNetwork.FIELD,owner.getId(),n));
                if(sustained)level.playSound(null,owner.blockPosition(),HexGodOfStories.RESUME.get(),SoundSource.PLAYERS,1.1f,1f);
                HexNetwork.sync(owner);
            }
            return expired;
        });
        PLAYER_GRACE.entrySet().removeIf(e->e.getValue()<=now);
        Map<UUID,Long> desired=new HashMap<>();
        Map<UUID,Entity> entities=new HashMap<>();
        Map<UUID,Double> rates=new HashMap<>();
        for(Field f:FIELDS) {
            if(f.level!=level)continue;
            double range=radius(f,now);
            if((range<=0||now<f.started)&&f.target==null)continue;
            List<Entity> affected=f.target==null
                ?level.getEntities((Entity)null,new AABB(f.center.subtract(range,range,range),f.center.add(range,range,range)),e->eligible(e,f,range))
                :Optional.ofNullable(level.getEntity(f.target)).filter(e->eligible(e,f)).map(List::of).orElse(List.of());
            long age=now-f.started;
            int count=0;
            for(Entity e:affected) {
                if(++count>MAX_ENTITIES_PER_FIELD&&f.expires!=Long.MAX_VALUE)break;
                entities.put(e.getUUID(),e);
                if(!f.stop){rates.merge(e.getUUID(),DILATION,Math::min);continue;}
                if(f.expires!=Long.MAX_VALUE&&age<RAMP) {
                    // Winding down: a smoothly shrinking rate, never a withheld tick.
                    rates.merge(e.getUUID(),Math.max(.04,1-age/(double)RAMP),Math::min);
                } else desired.merge(e.getUUID(),f.expires,Math::max);
            }
        }
        // Bodies released from a hold spend a few ticks coming back up to speed.
        RECOVERING.entrySet().removeIf(e->e.getValue()<now);
        for(var entry:RECOVERING.entrySet()) {
            Entity e=level.getEntity(entry.getKey());
            if(e==null||desired.containsKey(entry.getKey()))continue;
            entities.putIfAbsent(entry.getKey(),e);
            double progress=1-(entry.getValue()-now)/(double)RAMP;
            rates.merge(entry.getKey(),Math.max(RESUME_RATE,Math.min(1,progress)),Math::min);
        }

        List<Frozen> releasing=new ArrayList<>();
        Iterator<Map.Entry<UUID,Frozen>> it=FROZEN.entrySet().iterator();
        while(it.hasNext()) {
            var entry=it.next();Frozen s=entry.getValue();
            if(s.entity.level()!=level)continue;
            if(!desired.containsKey(entry.getKey())||s.entity.isRemoved()||s.entity instanceof ServerPlayer&&now>=s.expires) {
                if(s.entity instanceof ServerPlayer&&s.expires!=Long.MAX_VALUE){PLAYER_GRACE.put(entry.getKey(),now+100);desired.remove(entry.getKey());}
                it.remove();releasing.add(s);
            }
        }

        // Anything no longer being slowed is returned to full rate before the new rates are applied.
        for(UUID id:new ArrayList<>(SLOWED.keySet())) {
            Entity e=SLOWED.get(id);
            if(e==null||e.isRemoved()){SLOWED.remove(id);APPLIED.remove(id);RECOVERING.remove(id);continue;}
            if(e.level()!=level)continue;
            if(!rates.containsKey(id)||desired.containsKey(id))
                rate(e,desired.containsKey(id)?1:Math.min(1,rateOf(e)+.12));
        }
        ROTATION.clear();
        for(var entry:rates.entrySet()) {
            Entity e=entities.get(entry.getKey());
            if(e==null||desired.containsKey(entry.getKey()))continue;
            double previous=rateOf(e);
            double target=entry.getValue();
            double applied=previous+Mth.clamp(target-previous,-.12,.12);
            rate(e,applied);
            drift(e,applied);
            remember(e,applied);
        }

        for(var entry:desired.entrySet()) {
            Entity e=entities.get(entry.getKey());
            if(e==null)continue;
            Frozen s=FROZEN.get(entry.getKey());
            if(s==null) {
                rate(e,1);
                s=new Frozen(e,e.position(),e.getDeltaMovement(),e.getYRot(),e.getXRot(),e instanceof ServerPlayer&&entry.getValue()!=Long.MAX_VALUE?Math.min(entry.getValue(),now+60):entry.getValue());
                FROZEN.put(entry.getKey(),s);sync(s,true);
            }
            hold(e,s,now);
        }
        for(Frozen s:releasing)restore(s,now);
    }
    private static double radius(Field f,long now) {
        return f.stop&&f.target==null&&f.expires==Long.MAX_VALUE
            ?f.radius*Math.min(1,Math.max(0,(now-f.started)/(double)STOP_EXPANSION)):f.radius;
    }

    /** Pins a suspended body exactly where its moment caught it, orientation included. */
    private static void hold(Entity e,Frozen s,long now) {
        e.setPos(s.position);e.setYRot(s.yaw);e.setXRot(s.pitch);e.setDeltaMovement(Vec3.ZERO);e.hurtMarked=true;
        e.setOldPosAndRot();
        if(e instanceof LivingEntity l){l.setYHeadRot(s.yaw);l.yBodyRot=s.yaw;l.hurtTime=0;l.invulnerableTime=0;}
        if(e instanceof ServerPlayer p&&now%5==0)p.connection.teleport(s.position.x,s.position.y,s.position.z,s.yaw,s.pitch);
        if(now%20==0)sync(s,true);
    }

    /**
     * Sets how fast a body experiences time. Momentum is corrected only by the ratio between the old
     * and new rates, so holding a rate steady never compounds into a standstill, and a body that
     * leaves the field carries exactly the speed it came in with.
     */
    private static void rate(Entity e,double factor) {
        double previous=APPLIED.getOrDefault(e.getUUID(),1.0);
        if(Math.abs(previous-factor)<1e-4)return;
        Vec3 velocity=e.getDeltaMovement();
        if(previous>1e-6&&velocity.lengthSqr()>1e-8) {
            e.setDeltaMovement(velocity.scale(factor/previous));
            e.hurtMarked=true;
        }
        if(e instanceof LivingEntity living)attributes(living,factor);
        if(factor>=.999) {
            APPLIED.remove(e.getUUID());
            if(SLOWED.remove(e.getUUID())!=null)slowSync(e,false);
            return;
        }
        APPLIED.put(e.getUUID(),factor);
        if(SLOWED.put(e.getUUID(),e)==null)slowSync(e,true);
    }

    private static void attributes(LivingEntity living,double factor) {
        apply(living,Attributes.MOVEMENT_SPEED,DILATION_SPEED,factor);
        apply(living,Attributes.ATTACK_SPEED,DILATION_ATTACK,factor);
        apply(living,Attributes.FLYING_SPEED,DILATION_FLIGHT,factor);
    }
    private static void apply(LivingEntity living,Attribute type,UUID id,double factor) {
        AttributeInstance attribute=living.getAttribute(type);
        if(attribute==null)return;
        attribute.removeModifier(id);
        if(factor<.999)attribute.addTransientModifier(new AttributeModifier(id,"Temporal dilation",factor-1,AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /**
     * A head snapping round at full speed inside slow motion is as wrong as a body crossing the
     * ground at full speed. Angles are captured here, before the creature's own tick turns them, and
     * eased back afterwards so a turn takes as long as the movement it belongs to.
     */
    private static void remember(Entity e,double factor) {
        if(!(e instanceof LivingEntity living)||e instanceof ServerPlayer)return;
        ROTATION.put(e.getUUID(),new float[]{living.getYRot(),living.getXRot(),living.yHeadRot,living.yBodyRot,(float)factor});
    }

    /** Called after the level's entities have ticked; see {@link #remember}. */
    public static void afterTick(ServerLevel level) {
        if(ROTATION.isEmpty())return;
        for(var entry:ROTATION.entrySet()) {
            if(!(level.getEntity(entry.getKey()) instanceof LivingEntity living))continue;
            float[] before=entry.getValue();
            float factor=Mth.clamp(before[4],0,1);
            living.setYRot(Mth.rotLerp(factor,before[0],living.getYRot()));
            living.setXRot(Mth.rotLerp(factor,before[1],living.getXRot()));
            living.yHeadRot=Mth.rotLerp(factor,before[2],living.yHeadRot);
            living.yBodyRot=Mth.rotLerp(factor,before[3],living.yBodyRot);
        }
        ROTATION.clear();
    }

    /**
     * Gravity is a rate too. Left alone, a body moving at a third speed would fall at full speed and
     * an arrow would drop out of the air; countering most of the pull keeps the shape of the arc while
     * the journey along it stretches out.
     */
    private static void drift(Entity e,double factor) {
        // A player's fall is predicted on their own client, so pushing back against it from here
        // would rubber-band them. Their walking and striking are already slowed by attributes, which
        // the client is told about and predicts correctly.
        if(e instanceof ServerPlayer||e.isNoGravity()||e.onGround())return;
        double gravity=gravityOf(e);
        if(gravity<=0)return;
        e.setDeltaMovement(e.getDeltaMovement().add(0,gravity*(1-factor*factor),0));
    }
    private static double gravityOf(Entity e) {
        if(e instanceof AbstractArrow)return .05;
        if(e instanceof ThrowableProjectile)return .03;
        if(e instanceof AbstractHurtingProjectile)return 0;
        if(e instanceof ItemEntity||e instanceof FallingBlockEntity||e instanceof PrimedTnt)return .04;
        if(e instanceof ExperienceOrb)return .03;
        if(e instanceof LivingEntity living)return living.isFallFlying()||living.isInWater()?0:.08;
        return 0;
    }

    private static boolean eligible(Entity e,Field f) {return eligible(e,f,f.radius);}
    private static boolean eligible(Entity e,Field f,double range) {
        if(PLAYER_GRACE.getOrDefault(e.getUUID(),0L)>e.level().getGameTime())return false;
        if(e.isRemoved()||e.isSpectator()||e.getUUID().equals(f.owner)||e.getUUID().equals(f.exempt))return false;
        if(f.target==null&&!touches(e.getBoundingBox(),f.center,range))return false;
        return f.stop&&f.target==null||affectable(e);
    }
    /** The entire hitbox counts: a wing, boat, or projectile edge touching the bubble is enough. */
    private static boolean touches(AABB box,Vec3 centre,double radius) {
        double x=Mth.clamp(centre.x,box.minX,box.maxX)-centre.x;
        double y=Mth.clamp(centre.y,box.minY,box.maxY)-centre.y;
        double z=Mth.clamp(centre.z,box.minZ,box.maxZ)-centre.z;
        return x*x+y*y+z*z<=radius*radius;
    }
    /** Everything in the local world that visibly moves under its own steam. */
    private static boolean affectable(Entity e) {
        return e instanceof LivingEntity||e instanceof Projectile||e instanceof ItemEntity
            ||e instanceof FallingBlockEntity||e instanceof PrimedTnt||e instanceof ExperienceOrb
            ||e.getDeltaMovement().lengthSqr()>1.0E-8;
    }

    private static void restore(Frozen s,long now) {
        if(s.entity.isRemoved()){BANKED.remove(s.entity.getUUID());RECOVERING.remove(s.entity.getUUID());return;}
        // Spin back up rather than snapping to full speed, mirroring the way the field took hold. The
        // speed handed back has to match the rate the body is marked as running at, or the ramp's
        // proportional correction inflates it instead of easing it.
        boolean direct=s.expires==Long.MAX_VALUE;
        s.entity.setDeltaMovement(direct?s.velocity:s.velocity.scale(RESUME_RATE));s.entity.hurtMarked=true;
        if(!direct) {APPLIED.put(s.entity.getUUID(),RESUME_RATE);RECOVERING.put(s.entity.getUUID(),now+RAMP);}
        sync(s,false);
        Banked banked=BANKED.remove(s.entity.getUUID());
        if(banked!=null&&banked.damage>0&&s.entity instanceof LivingEntity living) {
            living.invulnerableTime=0;
            Entity attacker=s.entity.level() instanceof ServerLevel server&&banked.attacker!=null?server.getEntity(banked.attacker):null;
            var source=attacker instanceof net.minecraft.world.entity.player.Player player
                ?living.damageSources().playerAttack(player)
                :living.damageSources().magic();
            living.hurt(source,banked.damage);
            living.setDeltaMovement(direct?s.velocity:s.velocity.scale(RESUME_RATE));
            living.hurtMarked=true;
        }
    }

    private static CompoundTag tag(Frozen s,boolean active) {
        CompoundTag n=new CompoundTag();n.putBoolean("frozen",active);
        n.putDouble("x",s.position.x);n.putDouble("y",s.position.y);n.putDouble("z",s.position.z);
        n.putFloat("yaw",s.yaw);n.putFloat("pitch",s.pitch);
        n.putDouble("vx",s.velocity.x);n.putDouble("vy",s.velocity.y);n.putDouble("vz",s.velocity.z);
        return n;
    }
    private static void sync(Frozen s,boolean active) {HexNetwork.tracking(s.entity,new HexNetwork.Message(HexNetwork.FROZEN,s.entity.getId(),tag(s,active)));}
    private static void slowSync(Entity e,boolean active) {
        CompoundTag n=new CompoundTag();n.putBoolean("slowed",active);
        HexNetwork.tracking(e,new HexNetwork.Message(HexNetwork.SLOWED,e.getId(),n));
    }
    private static void broadcastField(ServerPlayer owner,Vec3 centre,double radius,boolean stop,long started,long expires,boolean area) {
        CompoundTag n=new CompoundTag();
        n.putBoolean("active",area);n.putDouble("x",centre.x);n.putDouble("y",centre.y);n.putDouble("z",centre.z);
        n.putDouble("radius",radius);n.putBoolean("stop",stop);n.putLong("started",started);n.putLong("expires",expires);
        HexNetwork.tracking(owner,new HexNetwork.Message(HexNetwork.FIELD,owner.getId(),n));
    }

    /** Brings a newly tracking client up to date on anything already suspended nearby. */
    public static void track(ServerPlayer viewer,Entity e) {
        if(slowed(e)){CompoundTag n=new CompoundTag();n.putBoolean("slowed",true);HexNetwork.to(viewer,new HexNetwork.Message(HexNetwork.SLOWED,e.getId(),n));}
        Frozen s=FROZEN.get(e.getUUID());
        if(s!=null)HexNetwork.to(viewer,new HexNetwork.Message(HexNetwork.FROZEN,e.getId(),tag(s,true)));
        for(Field f:FIELDS) {
            if(!f.owner.equals(e.getUUID())||f.target!=null)continue;
            CompoundTag n=new CompoundTag();
            n.putBoolean("active",true);n.putDouble("x",f.center.x);n.putDouble("y",f.center.y);n.putDouble("z",f.center.z);
            n.putDouble("radius",f.radius);n.putBoolean("stop",f.stop);n.putLong("started",f.started);n.putLong("expires",f.expires);
            HexNetwork.to(viewer,new HexNetwork.Message(HexNetwork.FIELD,e.getId(),n));
        }
    }
    public static void reset() {
        for(Frozen s:FROZEN.values())if(!s.entity.isRemoved()){s.entity.setDeltaMovement(s.velocity);s.entity.hurtMarked=true;sync(s,false);}
        FROZEN.clear();FIELDS.clear();WINDUPS.clear();
        for(Entity e:new ArrayList<>(SLOWED.values()))if(!e.isRemoved())rate(e,1);
        SLOWED.clear();APPLIED.clear();
        PLAYER_GRACE.clear();RECOVERING.clear();BANKED.clear();ROTATION.clear();
    }
}
