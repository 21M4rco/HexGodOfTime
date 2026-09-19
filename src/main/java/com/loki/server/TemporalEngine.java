package com.loki.server;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.*;
import java.util.*;

/** Each field owns its contribution. Removing one caster never releases another caster's suspension. */
public final class TemporalEngine {
    public record Field(UUID owner,ServerLevel level,Vec3 center,double radius,long expires,boolean stop,UUID target,UUID exempt) {}
    public record Frozen(Entity entity,Vec3 position,Vec3 velocity,float yaw,float pitch,long expires) {}
    private static final List<Field> FIELDS=new ArrayList<>();
    private static final Map<UUID,Frozen> FROZEN=new HashMap<>();
    private static final Map<UUID,Long> PLAYER_GRACE=new HashMap<>();
    private static final Map<UUID,Entity> SLOWED=new HashMap<>();
    private static final UUID DILATION_SPEED=UUID.fromString("65b293d4-1384-446d-902c-7a261ca31cf2");
    private static final int MAX_FIELDS=64,MAX_ENTITIES_PER_FIELD=192;
    public static boolean frozen(Entity e) {return FROZEN.containsKey(e.getUUID());}
    public static boolean skipTick(Entity e) {return frozen(e)||(SLOWED.containsKey(e.getUUID())&&Math.floorMod(e.level().getGameTime()+e.getId(),5)!=0);}
    public static boolean slowed(Entity e) {return SLOWED.containsKey(e.getUUID());}
    public static boolean owns(ServerPlayer p) {return FIELDS.stream().anyMatch(f->f.owner.equals(p.getUUID()));}
    public static boolean field(ServerPlayer p,boolean stop,Entity target,int ticks) {
        if(FIELDS.size()>=MAX_FIELDS)return false;
        if(p.isPassenger()||target!=null&&target.isPassenger())return false;
        UUID ally=LokiData.get(p).hasUUID("ally")?LokiData.get(p).getUUID("ally"):null;
        FIELDS.add(new Field(p.getUUID(),p.serverLevel(),p.position(),LokiData.get(p).getBoolean("ascended")?18:12,p.level().getGameTime()+ticks,stop,target==null?null:target.getUUID(),ally));
        LokiNetwork.fx(p,stop?"stop":"dilate");return true;
    }
    public static void exempt(ServerPlayer p,Entity ally) {
        LokiData.get(p).putUUID("ally",ally.getUUID());
        FIELDS.replaceAll(f->f.owner.equals(p.getUUID())?new Field(f.owner,f.level,f.center,f.radius,f.expires,f.stop,f.target,ally.getUUID()):f);
    }
    public static void clear(ServerPlayer p) {FIELDS.removeIf(f->f.owner.equals(p.getUUID()));LokiNetwork.fx(p,"resume");tick(p.serverLevel());}
    public static void tick(ServerLevel level) {
        long now=level.getGameTime();
        FIELDS.removeIf(f->f.level==level&&(f.expires<=now||level.getServer().getPlayerList().getPlayer(f.owner)==null||level.getServer().getPlayerList().getPlayer(f.owner).level()!=level||!level.getServer().getPlayerList().getPlayer(f.owner).isAlive()));
        Map<UUID,Long> desired=new HashMap<>();Map<UUID,Entity> entities=new HashMap<>();
        Map<UUID,Entity> desiredSlow=new HashMap<>();
        for(Field f:FIELDS) {
            if(f.level!=level)continue;
            List<Entity> affected=f.target==null?level.getEntities((Entity)null,new AABB(f.center.subtract(f.radius,f.radius,f.radius),f.center.add(f.radius,f.radius,f.radius)),e->eligible(e,f)):Optional.ofNullable(level.getEntity(f.target)).filter(e->eligible(e,f)).map(List::of).orElse(List.of());
            int count=0;for(Entity e:affected) {
                if(++count>MAX_ENTITIES_PER_FIELD)break;
                entities.put(e.getUUID(),e);
                if(f.stop)desired.merge(e.getUUID(),f.expires,Math::max);else desiredSlow.put(e.getUUID(),e);
            }
        }
        Iterator<Map.Entry<UUID,Entity>> oldSlow=SLOWED.entrySet().iterator();
        while(oldSlow.hasNext()){var e=oldSlow.next();if(e.getValue().level()==level&&!desiredSlow.containsKey(e.getKey())){slowSync(e.getValue(),false);oldSlow.remove();}}
        for(var e:desiredSlow.entrySet())if(!SLOWED.containsKey(e.getKey())){SLOWED.put(e.getKey(),e.getValue());slowSync(e.getValue(),true);}
        Iterator<Map.Entry<UUID,Frozen>> it=FROZEN.entrySet().iterator();
        while(it.hasNext()) {var entry=it.next();Frozen s=entry.getValue();if(s.entity.level()!=level)continue;
            if(!desired.containsKey(entry.getKey())||s.entity.isRemoved()||s.entity instanceof ServerPlayer && now>=s.expires) {if(s.entity instanceof ServerPlayer){PLAYER_GRACE.put(entry.getKey(),now+100);desired.remove(entry.getKey());}restore(s);it.remove();}
        }
        for(var entry:desired.entrySet()) {
            Entity e=entities.get(entry.getKey());
            Frozen s=FROZEN.get(entry.getKey());
            if(s==null) {s=new Frozen(e,e.position(),e.getDeltaMovement(),e.getYRot(),e.getXRot(),e instanceof ServerPlayer?Math.min(entry.getValue(),now+60):entry.getValue());FROZEN.put(entry.getKey(),s);sync(s,true);}
            e.setPos(s.position);e.setYRot(s.yaw);e.setXRot(s.pitch);e.setDeltaMovement(Vec3.ZERO);e.hurtMarked=true;
            if(e instanceof LivingEntity l){l.setYHeadRot(s.yaw);l.yBodyRot=s.yaw;}
            if(e instanceof ServerPlayer p&&now%5==0)p.connection.teleport(s.position.x,s.position.y,s.position.z,s.yaw,s.pitch);
            if(now%20==0)sync(s,true);
        }
    }
    private static boolean eligible(Entity e,Field f) {
        if(PLAYER_GRACE.getOrDefault(e.getUUID(),0L)>e.level().getGameTime())return false;
        if(e.isRemoved()||e.isSpectator()||e.isPassenger()||e.isVehicle()||e.getUUID().equals(f.owner)||e.getUUID().equals(f.exempt))return false;
        if(e instanceof ServerPlayer p&&p.isCreative())return false;
        if(e.position().distanceToSqr(f.center)>f.radius*f.radius&&f.target==null)return false;
        return e instanceof LivingEntity||e instanceof Projectile||e instanceof ItemEntity;
    }
    private static void restore(Frozen s) {if(!s.entity.isRemoved()){s.entity.setDeltaMovement(s.velocity);s.entity.hurtMarked=true;sync(s,false);}}
    private static CompoundTag tag(Frozen s,boolean active) {
        CompoundTag n=new CompoundTag();n.putBoolean("frozen",active);n.putDouble("x",s.position.x);n.putDouble("y",s.position.y);n.putDouble("z",s.position.z);n.putFloat("yaw",s.yaw);n.putFloat("pitch",s.pitch);n.putDouble("vx",s.velocity.x);n.putDouble("vy",s.velocity.y);n.putDouble("vz",s.velocity.z);return n;
    }
    private static void sync(Frozen s,boolean active) {LokiNetwork.tracking(s.entity,new LokiNetwork.Message(3,s.entity.getId(),tag(s,active)));}
    private static void slowSync(Entity e,boolean active) {
        if(e instanceof ServerPlayer p){for(var type:List.of(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED,net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)){var attr=p.getAttribute(type);if(attr!=null){attr.removeModifier(DILATION_SPEED);if(active)attr.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(DILATION_SPEED,"Temporal dilation",-.8,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_TOTAL));}}}
        CompoundTag n=new CompoundTag();n.putBoolean("slowed",active);LokiNetwork.tracking(e,new LokiNetwork.Message(7,e.getId(),n));
    }
    public static void track(ServerPlayer viewer,Entity e) {if(slowed(e)){CompoundTag n=new CompoundTag();n.putBoolean("slowed",true);LokiNetwork.to(viewer,new LokiNetwork.Message(7,e.getId(),n));}Frozen s=FROZEN.get(e.getUUID());if(s!=null)LokiNetwork.to(viewer,new LokiNetwork.Message(3,e.getId(),tag(s,true)));}
    public static void reset() {FROZEN.values().forEach(TemporalEngine::restore);FROZEN.clear();FIELDS.clear();SLOWED.values().forEach(e->slowSync(e,false));SLOWED.clear();PLAYER_GRACE.clear();}
}
