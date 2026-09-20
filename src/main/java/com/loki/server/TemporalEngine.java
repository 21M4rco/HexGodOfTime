package com.loki.server;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.*;
import java.util.*;

/**
 * Local temporal suspension. Each field owns its contribution, so removing one caster never releases another
 * caster's hold. Nothing here touches the server tick rate: affected entities simply have their own ticks
 * stepped down, then withheld. Entities do not snap to a halt — they decelerate over {@link #RAMP} ticks and
 * spin back up the same way — and harm dealt to a suspended body is banked until time resumes.
 */
public final class TemporalEngine {
    public record Field(UUID owner,ServerLevel level,Vec3 center,double radius,long started,long expires,boolean stop,UUID target,UUID exempt) {}
    public record Frozen(Entity entity,Vec3 position,Vec3 velocity,float yaw,float pitch,long expires) {}
    private record Banked(float damage,UUID attacker) {}
    private static final List<Field> FIELDS=new ArrayList<>();
    private static final Map<UUID,Frozen> FROZEN=new HashMap<>();
    private static final Map<UUID,Long> PLAYER_GRACE=new HashMap<>();
    private static final Map<UUID,Entity> SLOWED=new HashMap<>();
    private static final Map<UUID,Integer> RAMPING=new HashMap<>();
    private static final Map<UUID,Banked> BANKED=new HashMap<>();
    private static final UUID DILATION_SPEED=UUID.fromString("65b293d4-1384-446d-902c-7a261ca31cf2");
    private static final int MAX_FIELDS=64,MAX_ENTITIES_PER_FIELD=192;
    /** Ticks spent decelerating into, and accelerating out of, a full stop. */
    public static final int RAMP=7;
    private static final float MAX_BANKED=60;

    public static boolean frozen(Entity e) {return FROZEN.containsKey(e.getUUID());}
    public static boolean slowed(Entity e) {return SLOWED.containsKey(e.getUUID());}
    public static boolean owns(ServerPlayer p) {return FIELDS.stream().anyMatch(f->f.owner.equals(p.getUUID()));}
    public static boolean skipTick(Entity e) {
        if(frozen(e))return true;
        Integer stride=RAMPING.get(e.getUUID());
        if(stride!=null&&stride>1)return Math.floorMod(e.level().getGameTime()+e.getId(),stride)!=0;
        return SLOWED.containsKey(e.getUUID())&&Math.floorMod(e.level().getGameTime()+e.getId(),5)!=0;
    }

    public static boolean field(ServerPlayer p,boolean stop,Entity target,int ticks) {
        if(FIELDS.size()>=MAX_FIELDS)return false;
        if(p.isPassenger()||target!=null&&target.isPassenger())return false;
        UUID ally=LokiData.get(p).hasUUID("ally")?LokiData.get(p).getUUID("ally"):null;
        long now=p.level().getGameTime();
        double radius=LokiData.get(p).getBoolean("ascended")?18:12;
        FIELDS.add(new Field(p.getUUID(),p.serverLevel(),p.position(),radius,now,now+ticks,stop,target==null?null:target.getUUID(),ally));
        broadcastField(p,p.position(),radius,stop,now,now+ticks,target==null);
        LokiNetwork.fx(p,stop?"stop":"dilate");
        return true;
    }
    public static void exempt(ServerPlayer p,Entity ally) {
        LokiData.get(p).putUUID("ally",ally.getUUID());
        FIELDS.replaceAll(f->f.owner.equals(p.getUUID())?new Field(f.owner,f.level,f.center,f.radius,f.started,f.expires,f.stop,f.target,ally.getUUID()):f);
    }
    public static void clear(ServerPlayer p) {
        boolean had=FIELDS.removeIf(f->f.owner.equals(p.getUUID()));
        if(had) {
            CompoundTag n=new CompoundTag();n.putBoolean("active",false);
            LokiNetwork.tracking(p,new LokiNetwork.Message(LokiNetwork.FIELD,p.getId(),n));
            LokiNetwork.fx(p,"resume");
        }
        tick(p.serverLevel());
    }

    /** Harm aimed at a suspended body is held back and delivered the instant its moment resumes. */
    public static boolean bank(Entity victim,float amount,Entity attacker) {
        if(!frozen(victim))return false;
        Banked existing=BANKED.get(victim.getUUID());
        float total=Math.min(MAX_BANKED,(existing==null?0:existing.damage)+amount);
        BANKED.put(victim.getUUID(),new Banked(total,attacker==null?(existing==null?null:existing.attacker):attacker.getUUID()));
        LokiNetwork.fx(victim,"banked");
        return true;
    }

    private static boolean ticking;

    public static void tick(ServerLevel level) {
        if(ticking)return;
        ticking=true;
        try {run(level);} finally {ticking=false;}
    }

    private static void run(ServerLevel level) {
        long now=level.getGameTime();
        FIELDS.removeIf(f->{
            if(f.level!=level)return false;
            ServerPlayer owner=level.getServer().getPlayerList().getPlayer(f.owner);
            return f.expires<=now||owner==null||owner.level()!=level||!owner.isAlive();
        });
        Map<UUID,Long> desired=new HashMap<>();
        Map<UUID,Entity> entities=new HashMap<>();
        Map<UUID,Entity> desiredSlow=new HashMap<>();
        Map<UUID,Integer> desiredRamp=new HashMap<>();
        for(Field f:FIELDS) {
            if(f.level!=level)continue;
            List<Entity> affected=f.target==null
                ?level.getEntities((Entity)null,new AABB(f.center.subtract(f.radius,f.radius,f.radius),f.center.add(f.radius,f.radius,f.radius)),e->eligible(e,f))
                :Optional.ofNullable(level.getEntity(f.target)).filter(e->eligible(e,f)).map(List::of).orElse(List.of());
            long age=now-f.started;
            int count=0;
            for(Entity e:affected) {
                if(++count>MAX_ENTITIES_PER_FIELD)break;
                entities.put(e.getUUID(),e);
                if(!f.stop){desiredSlow.put(e.getUUID(),e);continue;}
                if(age<RAMP) {
                    // Still winding down: let the body keep ticking, just far more rarely each tick that passes.
                    int stride=1+(int)age*2;
                    desiredRamp.merge(e.getUUID(),stride,Math::max);
                    e.setDeltaMovement(e.getDeltaMovement().scale(1-age/(double)RAMP));
                    e.hurtMarked=true;
                } else desired.merge(e.getUUID(),f.expires,Math::max);
            }
        }
        RAMPING.keySet().removeIf(id->level.getEntity(id)!=null&&!desiredRamp.containsKey(id)&&!isResuming(id,now));
        RAMPING.putAll(desiredRamp);
        Iterator<Map.Entry<UUID,Entity>> oldSlow=SLOWED.entrySet().iterator();
        while(oldSlow.hasNext()){var e=oldSlow.next();if(e.getValue().level()==level&&!desiredSlow.containsKey(e.getKey())){slowSync(e.getValue(),false);oldSlow.remove();}}
        for(var e:desiredSlow.entrySet())if(!SLOWED.containsKey(e.getKey())){SLOWED.put(e.getKey(),e.getValue());slowSync(e.getValue(),true);}
        List<Frozen> releasing=new ArrayList<>();
        Iterator<Map.Entry<UUID,Frozen>> it=FROZEN.entrySet().iterator();
        while(it.hasNext()) {
            var entry=it.next();Frozen s=entry.getValue();
            if(s.entity.level()!=level)continue;
            if(!desired.containsKey(entry.getKey())||s.entity.isRemoved()||s.entity instanceof ServerPlayer&&now>=s.expires) {
                if(s.entity instanceof ServerPlayer){PLAYER_GRACE.put(entry.getKey(),now+100);desired.remove(entry.getKey());}
                it.remove();releasing.add(s);
            }
        }
        for(var entry:desired.entrySet()) {
            Entity e=entities.get(entry.getKey());
            Frozen s=FROZEN.get(entry.getKey());
            if(s==null) {
                s=new Frozen(e,e.position(),e.getDeltaMovement(),e.getYRot(),e.getXRot(),e instanceof ServerPlayer?Math.min(entry.getValue(),now+60):entry.getValue());
                FROZEN.put(entry.getKey(),s);RAMPING.remove(entry.getKey());sync(s,true);
            }
            e.setPos(s.position);e.setYRot(s.yaw);e.setXRot(s.pitch);e.setDeltaMovement(Vec3.ZERO);e.hurtMarked=true;
            if(e instanceof LivingEntity l){l.setYHeadRot(s.yaw);l.yBodyRot=s.yaw;l.hurtTime=0;l.invulnerableTime=0;}
            if(e instanceof ServerPlayer p&&now%5==0)p.connection.teleport(s.position.x,s.position.y,s.position.z,s.yaw,s.pitch);
            if(now%20==0)sync(s,true);
        }
        RESUMING.values().removeIf(v->v<now);
        for(Frozen s:releasing)restore(s,now);
    }

    private static final Map<UUID,Long> RESUMING=new HashMap<>();
    private static boolean isResuming(UUID id,long now) {Long end=RESUMING.get(id);return end!=null&&end>now;}

    private static boolean eligible(Entity e,Field f) {
        if(PLAYER_GRACE.getOrDefault(e.getUUID(),0L)>e.level().getGameTime())return false;
        if(e.isRemoved()||e.isSpectator()||e.isPassenger()||e.isVehicle()||e.getUUID().equals(f.owner)||e.getUUID().equals(f.exempt))return false;
        if(e instanceof ServerPlayer p&&p.isCreative())return false;
        if(e.position().distanceToSqr(f.center)>f.radius*f.radius&&f.target==null)return false;
        return e instanceof LivingEntity||e instanceof Projectile||e instanceof ItemEntity;
    }

    private static void restore(Frozen s,long now) {
        if(s.entity.isRemoved())return;
        s.entity.setDeltaMovement(s.velocity);s.entity.hurtMarked=true;
        // Spin back up rather than snapping to full speed, mirroring the way the field took hold.
        RAMPING.put(s.entity.getUUID(),RAMP*2-1);
        RESUMING.put(s.entity.getUUID(),now+RAMP);
        sync(s,false);
        Banked banked=BANKED.remove(s.entity.getUUID());
        if(banked!=null&&banked.damage>0&&s.entity instanceof LivingEntity living) {
            living.invulnerableTime=0;
            Entity attacker=s.entity.level() instanceof ServerLevel server&&banked.attacker!=null?server.getEntity(banked.attacker):null;
            var source=attacker instanceof net.minecraft.world.entity.player.Player player
                ?living.damageSources().playerAttack(player)
                :living.damageSources().magic();
            living.hurt(source,banked.damage);
            LokiNetwork.fx(living,"release");
        }
    }

    private static CompoundTag tag(Frozen s,boolean active) {
        CompoundTag n=new CompoundTag();n.putBoolean("frozen",active);
        n.putDouble("x",s.position.x);n.putDouble("y",s.position.y);n.putDouble("z",s.position.z);
        n.putFloat("yaw",s.yaw);n.putFloat("pitch",s.pitch);
        n.putDouble("vx",s.velocity.x);n.putDouble("vy",s.velocity.y);n.putDouble("vz",s.velocity.z);
        return n;
    }
    private static void sync(Frozen s,boolean active) {LokiNetwork.tracking(s.entity,new LokiNetwork.Message(LokiNetwork.FROZEN,s.entity.getId(),tag(s,active)));}
    private static void slowSync(Entity e,boolean active) {
        if(e instanceof ServerPlayer p) {
            for(var type:List.of(Attributes.MOVEMENT_SPEED,Attributes.ATTACK_SPEED)) {
                AttributeInstance attr=p.getAttribute(type);
                if(attr==null)continue;
                attr.removeModifier(DILATION_SPEED);
                if(active)attr.addTransientModifier(new AttributeModifier(DILATION_SPEED,"Temporal dilation",-.8,AttributeModifier.Operation.MULTIPLY_TOTAL));
            }
        }
        CompoundTag n=new CompoundTag();n.putBoolean("slowed",active);
        LokiNetwork.tracking(e,new LokiNetwork.Message(LokiNetwork.SLOWED,e.getId(),n));
    }
    private static void broadcastField(ServerPlayer owner,Vec3 centre,double radius,boolean stop,long started,long expires,boolean area) {
        CompoundTag n=new CompoundTag();
        n.putBoolean("active",area);n.putDouble("x",centre.x);n.putDouble("y",centre.y);n.putDouble("z",centre.z);
        n.putDouble("radius",radius);n.putBoolean("stop",stop);n.putLong("started",started);n.putLong("expires",expires);
        LokiNetwork.tracking(owner,new LokiNetwork.Message(LokiNetwork.FIELD,owner.getId(),n));
    }

    /** Brings a newly tracking client up to date on anything already suspended nearby. */
    public static void track(ServerPlayer viewer,Entity e) {
        if(slowed(e)){CompoundTag n=new CompoundTag();n.putBoolean("slowed",true);LokiNetwork.to(viewer,new LokiNetwork.Message(LokiNetwork.SLOWED,e.getId(),n));}
        Frozen s=FROZEN.get(e.getUUID());
        if(s!=null)LokiNetwork.to(viewer,new LokiNetwork.Message(LokiNetwork.FROZEN,e.getId(),tag(s,true)));
        for(Field f:FIELDS) {
            if(!f.owner.equals(e.getUUID())||f.target!=null)continue;
            CompoundTag n=new CompoundTag();
            n.putBoolean("active",true);n.putDouble("x",f.center.x);n.putDouble("y",f.center.y);n.putDouble("z",f.center.z);
            n.putDouble("radius",f.radius);n.putBoolean("stop",f.stop);n.putLong("started",f.started);n.putLong("expires",f.expires);
            LokiNetwork.to(viewer,new LokiNetwork.Message(LokiNetwork.FIELD,e.getId(),n));
        }
    }
    public static void reset() {
        for(Frozen s:FROZEN.values())if(!s.entity.isRemoved()){s.entity.setDeltaMovement(s.velocity);s.entity.hurtMarked=true;sync(s,false);}
        FROZEN.clear();FIELDS.clear();
        SLOWED.values().forEach(e->slowSync(e,false));SLOWED.clear();
        PLAYER_GRACE.clear();RAMPING.clear();RESUMING.clear();BANKED.clear();
    }
}
