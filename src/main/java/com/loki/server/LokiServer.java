package com.loki.server;

import com.loki.Loki;
import com.loki.data.*;
import com.loki.entity.*;
import com.loki.network.LokiNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

public final class LokiServer {
    public record Moment(Vec3 position,float yaw,float pitch,float health) {}
    private record Hold(Entity entity,boolean gravity,boolean ai,long end) {}
    private record Charm(Mob mob,UUID owner,long end,UUID previous) {}
    private record Strike(int weapon,int combo,long contact,long end) {}
    private static final Map<UUID,ArrayDeque<Moment>> HISTORY=new HashMap<>();
    private static final Map<UUID,List<Hold>> HELD=new HashMap<>();
    private static final Map<UUID,Charm> CHARMS=new HashMap<>();
    private static final Map<UUID,Strike> STRIKES=new HashMap<>();
    private static final Map<UUID,Long> INPUT=new HashMap<>(),TRAINING=new HashMap<>();
    private static final Map<UUID,List<UUID>> ILLUSIONS=new HashMap<>();
    private static final Map<UUID,ArrayDeque<Vec3>> WATCHED=new HashMap<>();
    public static boolean validTarget(ServerPlayer p,Entity e) {return e!=p&&e.isAlive()&&!e.isSpectator()&&!p.isAlliedTo(e)&&(!(e instanceof Player q)||!q.isCreative()&&p.canHarmPlayer(q));}
    public static Entity target(ServerPlayer p,double range) {
        Vec3 start=p.getEyePosition(),end=start.add(p.getLookAngle().scale(range));
        BlockHitResult block=p.level().clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        double max=start.distanceToSqr(block.getLocation());Entity best=null;
        for(Entity e:p.level().getEntities(p,p.getBoundingBox().expandTowards(p.getLookAngle().scale(range)).inflate(1),e->(e instanceof LivingEntity||e instanceof ItemEntity)&&e.isAlive()&&!e.isSpectator())) {
            Optional<Vec3> hit=e.getBoundingBox().inflate(.25).clip(start,end);
            if(hit.isPresent()&&start.distanceToSqr(hit.get())<max){best=e;max=start.distanceToSqr(hit.get());}
        }return best;
    }
    public static void input(ServerPlayer p,int action,int value) {
        long now=LokiData.now(p);
        if(action==5){if(now-INPUT.getOrDefault(p.getUUID(),-100L)<2)return;INPUT.put(p.getUUID(),now);if(value>=0&&value<Ability.values().length&&LokiData.unlocked(p,Ability.at(value)))LokiData.get(p).putInt("selected",value);LokiNetwork.sync(p);return;}
        if(TemporalEngine.frozen(p)||p.isSpectator())return;
        if(now-INPUT.getOrDefault(p.getUUID(),-100L)<(TemporalEngine.slowed(p)?15:3))return;INPUT.put(p.getUUID(),now);
        if(action==2){release(p,false);TemporalEngine.clear(p);return;}
        if(action==4){weapon(p,value!=0);return;}
        Ability a=action==3?Ability.ASCENSION:LokiData.selected(p);
        if(action==6){LokiNetwork.sync(p);return;}
        if(action==1&&secondary(p,a)) {LokiNetwork.sync(p);return;}
        if(!LokiData.unlocked(p,a)){notice(p,"This chapter of your story is still locked.");return;}
        if(LokiData.cooldown(p,a)>0){notice(p,"The spell is recovering.");return;}
        if(LokiData.energy(p)<a.cost){notice(p,"Not enough Temporal Energy.");return;}
        boolean success=cast(p,a,action==1);
        if(success) {
            LokiData.spend(p,a.cost);LokiData.get(p).putLong("cd_"+a.name(),now+a.cooldown);
            reward(p,a.discipline,90);LokiNetwork.sync(p);
        }
    }
    private static boolean secondary(ServerPlayer p,Ability a) {
        if(a==Ability.TIME_STOP||a==Ability.SLOW_FIELD){TemporalEngine.clear(p);return true;}
        if(a==Ability.DUPLICATE){clearIllusions(p);return true;}
        if(a==Ability.MASQUERADE){LokiData.get(p).remove("disguise");LokiNetwork.fx(p,"disguise");return true;}
        if(a==Ability.TELEKINESIS&&HELD.containsKey(p.getUUID())){release(p,true);return true;}
        if(a==Ability.ENCHANT){direct(p);return true;}
        if(a==Ability.SELECTIVE_STOP&&LokiData.unlocked(p,a)){Entity t=target(p,20);if(t!=null){TemporalEngine.exempt(p,t);notice(p,"Your chosen companion may walk through your stopped time.");}return true;}
        if(a==Ability.DAGGERS||a==Ability.TWIN_DAGGERS||a==Ability.LAEVATEINN){dismissWeapons(p);return true;}
        return false;
    }
    private static boolean cast(ServerPlayer p,Ability a,boolean secondary) {
        Entity t=target(p,24);Vec3 look=p.getLookAngle();long now=LokiData.now(p);
        switch(a) {
            case DUPLICATE -> {return duplicate(p,false,false);}
            case MIRAGE -> {if(!duplicate(p,true,false))return false;LokiData.get(p).putLong("vanishUntil",now+50);p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY,50,0,false,false));return true;}
            case PROJECTION_SWAP -> {if(secondary)return duplicate(p,false,true);return swap(p);}
            case MASQUERADE -> {
                if(t==null)return false;
                String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(t.getType()).toString();
                if(!(t instanceof Player)&&!Set.of("minecraft:zombie","minecraft:skeleton","minecraft:villager","minecraft:pillager","minecraft:witch","minecraft:stray","minecraft:husk").contains(type)){notice(p,"Choose a humanoid target.");return false;}
                CompoundTag d=new CompoundTag();d.putString("type",type);d.putUUID("uuid",t.getUUID());d.putLong("start",now);d.putLong("end",now+600);LokiData.get(p).put("disguise",d);gesture(p,"illusion","disguise",Loki.ILLUSION_SOUND.get());return true;
            }
            case FALSE_TERRAIN -> {
                Vec3 location=safeAim(p,10);if(location==null)return false;CompoundTag n=new CompoundTag();n.putDouble("x",Math.floor(location.x));n.putDouble("y",Math.floor(location.y));n.putDouble("z",Math.floor(location.z));n.putLong("until",now+160);
                if(t instanceof ServerPlayer viewer)LokiNetwork.to(viewer,new LokiNetwork.Message(8,p.getId(),n));
                else for(ServerPlayer viewer:p.serverLevel().players())if(viewer.distanceToSqr(location)<1600)LokiNetwork.to(viewer,new LokiNetwork.Message(8,p.getId(),n));
                gesture(p,"illusion","cast",Loki.ILLUSION_SOUND.get());return true;
            }
            case BOLT -> {SpellProjectile.cast(p,p.getEyePosition().add(look.scale(.5)),look,secondary?1:0,false);gesture(p,"bolt","cast",Loki.SORCERY.get());return true;}
            case PUSH -> {for(Entity e:p.level().getEntities(p,p.getBoundingBox().inflate(5),e->validTarget(p,e))) {Vec3 away=e.position().subtract(p.position()).normalize();e.setDeltaMovement(away.scale(1.1).add(0,.25,0));e.hurtMarked=true;}gesture(p,"push","push",Loki.SORCERY.get());return true;}
            case BLINK -> {Vec3 destination=safeAim(p,8+LokiData.mastery(p,Discipline.SORCERY)/90.0);if(destination==null)return false;gesture(p,"blink","depart",Loki.TELEPORT.get());teleport(p,destination);LokiNetwork.fx(p,"arrive");return true;}
            case WARD -> {LokiData.get(p).putLong("wardUntil",now+100);gesture(p,"ward","ward",Loki.SORCERY.get());return true;}
            case TELEKINESIS -> {
                if(HELD.containsKey(p.getUUID())){release(p,false);return true;}
                if(t==null||!validTarget(p,t)||t.isPassenger()||t.isVehicle()||TemporalEngine.frozen(t))return false;
                double mass=t.getBbWidth()*t.getBbWidth()*t.getBbHeight();if(mass>2+LokiData.mastery(p,Discipline.SORCERY)*.035){notice(p,"This creature exceeds your control.");return false;}
                hold(p,t);gesture(p,"telekinesis","hold",Loki.SORCERY.get());return true;
            }
            case DAGGERS,TWIN_DAGGERS,LAEVATEINN -> {return conjure(p,a);}
            case ENCHANT -> {
                if(t instanceof ServerPlayer other&&validTarget(p,t)){other.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.CONFUSION,50,0,false,false));LokiNetwork.fx(other,"enchant");gesture(p,"enchant","cast",Loki.SORCERY.get());return true;}
                if(!(t instanceof Mob mob)||!validTarget(p,mob)||mob.getMaxHealth()>30+LokiData.mastery(p,Discipline.ENCHANTMENT)*.25)return false;
                CHARMS.put(mob.getUUID(),new Charm(mob,p.getUUID(),now+240+LokiData.mastery(p,Discipline.ENCHANTMENT)/2,mob.getTarget()==null?null:mob.getTarget().getUUID()));mob.setTarget(null);gesture(p,"enchant","enchant",Loki.ILLUSION_SOUND.get());LokiNetwork.fx(mob,"enchant");return true;
            }
            case MEMORY -> {if(t==null)return false;ArrayDeque<Vec3> path=WATCHED.get(t.getUUID());if(path==null||path.isEmpty()){notice(p,"Stay near the target while its memory gathers.");return false;}CompoundTag n=new CompoundTag();int i=0;for(Vec3 v:path){CompoundTag point=new CompoundTag();point.putDouble("x",v.x);point.putDouble("y",v.y);point.putDouble("z",v.z);n.put("p"+i++,point);}n.putInt("count",i);LokiNetwork.tracking(p,new LokiNetwork.Message(5,t.getId(),n));gesture(p,"enchant","memory",Loki.ILLUSION_SOUND.get());return true;}
            case TIME_SLIP,REWIND -> {
                ArrayDeque<Moment> h=HISTORY.get(p.getUUID());if(h==null||h.size()<10)return false;
                List<Moment> history=new ArrayList<>(h);int mastery=LokiData.mastery(p,Discipline.TEMPORAL);
                int index=a==Ability.REWIND?0:mastery<160?p.getRandom().nextInt(Math.max(1,history.size()-6)):Math.max(0,history.size()-16);
                Moment m=history.get(index);if(!safe(p,m.position))return false;
                gesture(p,"time_slip","slip",Loki.SLIP.get());teleport(p,m.position);p.setYRot(m.yaw);p.setXRot(m.pitch);
                if(a==Ability.REWIND)p.setHealth(Math.min(p.getMaxHealth(),Math.min(p.getHealth()+4,Math.max(p.getHealth(),m.health))));
                LokiNetwork.fx(p,"slip");return true;
            }
            case SLOW_FIELD,TIME_STOP -> {boolean stop=a==Ability.TIME_STOP;if(!TemporalEngine.field(p,stop,null,stop?120:180))return false;gesture(p,"time_stop",stop?"stop":"dilate",Loki.STOP.get());return true;}
            case SELECTIVE_STOP -> {if(t==null||!validTarget(p,t)||!TemporalEngine.field(p,true,t,t instanceof Player?40:100))return false;gesture(p,"time_stop","bind",Loki.STOP.get());return true;}
            case THREADS -> {if(t==null||!validTarget(p,t))return false;if(secondary)hold(p,t);else if(!TemporalEngine.field(p,true,t,t instanceof Player?40:140))return false;CompoundTag n=new CompoundTag();n.putInt("target",t.getId());n.putLong("until",now+100);LokiNetwork.tracking(p,new LokiNetwork.Message(6,p.getId(),n));gesture(p,"threads","bind",Loki.SORCERY.get());return true;}
            case ASCENSION -> {boolean on=!LokiData.get(p).getBoolean("ascended");LokiData.get(p).putBoolean("ascended",on);LokiData.get(p).putLong("transformStart",now);gesture(p,"ascend",on?"ascend":"dismiss",Loki.ASCEND.get());return true;}
        }return false;
    }
    private static boolean conjure(ServerPlayer p,Ability a) {
        if(!p.getMainHandItem().isEmpty()&&!(p.getMainHandItem().getItem() instanceof ConjuredWeapon)){notice(p,"Free your main hand to conjure.");return false;}
        if(a==Ability.TWIN_DAGGERS&&!p.getOffhandItem().isEmpty()&&!(p.getOffhandItem().getItem() instanceof ConjuredWeapon)){notice(p,"Free your other hand for twin daggers.");return false;}
        ItemStack item=new ItemStack(a==Ability.LAEVATEINN?Loki.LAEVATEINN.get():Loki.DAGGER.get());item.getOrCreateTag().putUUID("conjurer",p.getUUID());item.getOrCreateTag().putLong("formed",LokiData.now(p));
        p.setItemInHand(InteractionHand.MAIN_HAND,item);if(a==Ability.TWIN_DAGGERS)p.setItemInHand(InteractionHand.OFF_HAND,item.copy());
        gesture(p,"conjure","conjure",Loki.CONJURE.get());return true;
    }
    private static void dismissWeapons(ServerPlayer p) {for(InteractionHand hand:InteractionHand.values())if(p.getItemInHand(hand).getItem() instanceof ConjuredWeapon&&p.getItemInHand(hand).hasTag()&&p.getItemInHand(hand).getTag().hasUUID("conjurer"))p.setItemInHand(hand,ItemStack.EMPTY);}
    public static void weapon(ServerPlayer p,boolean secondary) {
        if(TemporalEngine.frozen(p)||!p.isAlive()||p.isSpectator()||!(p.getMainHandItem().getItem() instanceof ConjuredWeapon w))return;
        long now=LokiData.now(p);Strike prior=STRIKES.get(p.getUUID());if(prior!=null&&prior.end>now)return;
        int combo=prior==null||now-prior.end>18?0:(prior.combo+1)%4;
        if(secondary&&w.kind==0) {SpellProjectile.cast(p,p.getEyePosition(),p.getLookAngle(),2,false);gesture(p,"dagger_throw","conjure",Loki.CONJURE.get());STRIKES.put(p.getUUID(),new Strike(0,combo,0,now+14));return;}
        if(secondary&&w.kind==2){Entity t=target(p,4);if(t!=null&&validTarget(p,t))TemporalEngine.field(p,true,t,30);gesture(p,"time_stop","bind",Loki.STOP.get());STRIKES.put(p.getUUID(),new Strike(2,combo,0,now+40));return;}
        int windup=w.kind==1?8:4,recovery=w.kind==1?19:10;
        boolean twin=p.getOffhandItem().is(Loki.DAGGER.get());
        STRIKES.put(p.getUUID(),new Strike(w.kind,combo,now+windup,now+recovery));
        LokiNetwork.animate(p,(w.kind==1?"sword_":twin?"twin_":"dagger_")+combo);LokiNetwork.fx(p,"slash");
    }
    public static void tick(ServerPlayer p) {
        long now=LokiData.now(p);CompoundTag d=LokiData.get(p);
        if(!p.isAlive())return;
        if(now%4==0&&!TemporalEngine.frozen(p)) {ArrayDeque<Moment> h=HISTORY.computeIfAbsent(p.getUUID(),k->new ArrayDeque<>());h.addLast(new Moment(p.position(),p.getYRot(),p.getXRot(),p.getHealth()));while(h.size()>50)h.removeFirst();}
        int temporal=LokiData.mastery(p,Discipline.TEMPORAL);
        if(now%200==0&&temporal<160&&LokiData.unlocked(p,Ability.TIME_SLIP)&&!TemporalEngine.frozen(p)&&!p.isPassenger()&&p.getRandom().nextInt(5)==0&&LokiData.energy(p)>=15&&LokiData.cooldown(p,Ability.TIME_SLIP)==0){if(cast(p,Ability.TIME_SLIP,false)){LokiData.spend(p,15);LokiData.get(p).putLong("cd_TIME_SLIP",now+400);reward(p,Discipline.TEMPORAL,120);}}
        if(now%20==0) {LokiData.energy(p,LokiData.energy(p)+(d.getBoolean("ascended")?4:2));LokiNetwork.sync(p);}
        if(d.contains("disguise")&&d.getCompound("disguise").getLong("end")<now){d.remove("disguise");LokiNetwork.sync(p);}
        if(now%5==0){for(LivingEntity e:p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(12),e->e.isAlive())){if(WATCHED.size()>256&&!WATCHED.containsKey(e.getUUID()))break;ArrayDeque<Vec3> q=WATCHED.computeIfAbsent(e.getUUID(),k->new ArrayDeque<>());q.addLast(e.position());while(q.size()>24)q.removeFirst();}}
        if(HELD.containsKey(p.getUUID())) {
            List<Hold> holds=HELD.get(p.getUUID());boolean invalid=TemporalEngine.frozen(p)||holds.stream().anyMatch(h->!h.entity.isAlive()||h.entity.level()!=p.level()||h.end<now||p.distanceToSqr(h.entity)>900);
            if(invalid)release(p,false);
            else for(int i=0;i<holds.size();i++) {Entity e=holds.get(i).entity;Vec3 goal=p.getEyePosition().add(p.getLookAngle().scale(3.5+i*.8)).add(0,-e.getBbHeight()*.4,0);Vec3 move=goal.subtract(e.position()).scale(.32);double len=move.length();if(len>1.2)move=move.scale(1.2/len);e.setDeltaMovement(Vec3.ZERO);e.move(MoverType.SELF,move);e.hurtMarked=true;e.fallDistance=0;if(e instanceof ServerPlayer target)target.connection.teleport(e.getX(),e.getY(),e.getZ(),e.getYRot(),e.getXRot());}
        }
        Strike strike=STRIKES.get(p.getUUID());if(strike!=null&&strike.contact==now) {
            double reach=strike.weapon==1?4:2.8;
            for(LivingEntity e:p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(reach),e->validTarget(p,e)&&p.hasLineOfSight(e))) {
                Vec3 direction=e.getEyePosition().subtract(p.getEyePosition()).normalize();if(direction.dot(p.getLookAngle())<.35||p.distanceToSqr(e)>reach*reach)continue;
                e.hurt(p.damageSources().playerAttack(p),strike.weapon==1?7:4);e.knockback(.25,p.getX()-e.getX(),p.getZ()-e.getZ());reward(p,Discipline.CONJURATION,55);LokiNetwork.fx(e,"impact");
            }
        }
    }
    public static void tickLevel(ServerLevel level) {
        long now=level.getGameTime();
        Iterator<Charm> it=CHARMS.values().iterator();while(it.hasNext()){Charm c=it.next();if(c.mob.level()!=level)continue;ServerPlayer owner=level.getServer().getPlayerList().getPlayer(c.owner);
            if(c.end<now||owner==null||owner.level()!=level||!c.mob.isAlive()){if(c.previous!=null&&level.getEntity(c.previous) instanceof LivingEntity old)c.mob.setTarget(old);it.remove();continue;}
            if(now%10==0){if(c.mob.getTarget()==owner)c.mob.setTarget(null);if(c.mob.getTarget()==null&&c.mob.distanceToSqr(owner)>9)c.mob.getNavigation().moveTo(owner,1.05);}
        }
        if(now%200==0)WATCHED.entrySet().removeIf(e->level.getEntity(e.getKey())==null);
    }
    public static boolean charmedAgainst(Mob mob,LivingEntity target) {Charm c=CHARMS.get(mob.getUUID());return c!=null&&target!=null&&target.getUUID().equals(c.owner);}
    private static void direct(ServerPlayer p) {Entity t=target(p,24);Vec3 point=p.level().clip(new ClipContext(p.getEyePosition(),p.getEyePosition().add(p.getLookAngle().scale(24)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getLocation();for(Charm c:CHARMS.values())if(c.owner.equals(p.getUUID())){if(t instanceof LivingEntity l&&validTarget(p,l)&&l!=c.mob)c.mob.setTarget(l);else{c.mob.setTarget(null);c.mob.getNavigation().moveTo(point.x,point.y,point.z,1.1);}}}
    private static void hold(ServerPlayer p,Entity t) {
        if(HELD.values().stream().flatMap(List::stream).anyMatch(h->h.entity==t))return;
        boolean ai=t instanceof Mob m&&m.isNoAi();List<Hold> list=HELD.computeIfAbsent(p.getUUID(),k->new ArrayList<>());if(list.size()>=3)return;
        list.add(new Hold(t,t.isNoGravity(),ai,LokiData.now(p)+(t instanceof Player?40:180)));t.setNoGravity(true);if(t instanceof Mob m){m.setNoAi(true);m.getNavigation().stop();}LokiData.get(p).putInt("held",t.getId());
        CompoundTag n=new CompoundTag();n.putInt("target",t.getId());n.putLong("until",LokiData.now(p)+180);LokiNetwork.tracking(p,new LokiNetwork.Message(6,p.getId(),n));
    }
    public static void release(ServerPlayer p,boolean thrown) {List<Hold> list=HELD.remove(p.getUUID());if(list==null)return;for(Hold h:list){h.entity.setNoGravity(h.gravity);if(h.entity instanceof Mob m)m.setNoAi(h.ai);h.entity.setDeltaMovement(thrown?p.getLookAngle().scale(2.2).add(0,.2,0):Vec3.ZERO);h.entity.hurtMarked=true;}LokiData.get(p).remove("held");LokiNetwork.animate(p,thrown?"push":"__clear__");LokiNetwork.sync(p);}
    private static List<IllusionEntity> illusions(ServerPlayer p) {
        List<UUID> ids=ILLUSIONS.computeIfAbsent(p.getUUID(),k->new ArrayList<>());ids.removeIf(id->!(p.serverLevel().getEntity(id) instanceof IllusionEntity));List<IllusionEntity> list=new ArrayList<>();for(UUID id:ids)list.add((IllusionEntity)p.serverLevel().getEntity(id));return list;
    }
    private static boolean duplicate(ServerPlayer p,boolean multiple,boolean aimed) {
        int max=1+LokiData.mastery(p,Discipline.MISCHIEF)/180;List<IllusionEntity> existing=illusions(p);if(existing.size()>=max){notice(p,"Your projections are already at capacity.");return false;}
        int count=multiple?max-existing.size():1,spawned=0;
        for(int i=0;i<count;i++){double angle=p.getYRot()*Math.PI/180+i*Math.PI*2/count;Vec3 pos=aimed?safeAim(p,12):p.position().add(Math.cos(angle)*2,0,Math.sin(angle)*2);if(pos==null||!safe(p,pos))continue;
            IllusionEntity e=new IllusionEntity(Loki.ILLUSION.get(),p.level());e.setPos(pos);e.configure(p,new IllusionEntity.Spec(240+LokiData.mastery(p,Discipline.MISCHIEF)/2,IllusionEntity.Behavior.values()[(i+existing.size()+p.getRandom().nextInt(7))%7],true,false,true));p.level().addFreshEntity(e);ILLUSIONS.get(p.getUUID()).add(e.getUUID());spawned++;}
        if(spawned>0)gesture(p,"illusion","cast",Loki.ILLUSION_SOUND.get());return spawned>0;
    }
    private static boolean swap(ServerPlayer p) {IllusionEntity e=illusions(p).stream().filter(q->p.distanceToSqr(q)<900&&safe(p,q.position())).min(Comparator.comparingDouble(q->p.distanceToSqr(q))).orElse(null);if(e==null)return false;Vec3 old=p.position();gesture(p,"blink","depart",Loki.TELEPORT.get());teleport(p,e.position());e.setPos(old);LokiNetwork.fx(p,"arrive");return true;}
    public static void clearIllusions(ServerPlayer p) {illusions(p).forEach(IllusionEntity::dispel);ILLUSIONS.remove(p.getUUID());}
    public static boolean safe(ServerPlayer p,Vec3 pos) {return p.level().hasChunkAt(BlockPos.containing(pos))&&p.level().getWorldBorder().isWithinBounds(BlockPos.containing(pos))&&p.level().noCollision(p,p.getBoundingBox().move(pos.subtract(p.position())))&&!p.level().containsAnyLiquid(p.getBoundingBox().move(pos.subtract(p.position())));}
    private static Vec3 safeAim(ServerPlayer p,double range) {Vec3 from=p.getEyePosition();BlockHitResult hit=p.level().clip(new ClipContext(from,from.add(p.getLookAngle().scale(range)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));Vec3 goal=hit.getLocation().subtract(p.getLookAngle().scale(.8)).add(0,-.5,0);for(int i=0;i<8;i++){Vec3 v=goal.add(0,i*.5,0);if(safe(p,v))return v;}return null;}
    private static void teleport(ServerPlayer p,Vec3 v) {release(p,false);p.stopRiding();p.connection.teleport(v.x,v.y,v.z,p.getYRot(),p.getXRot());p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;}
    public static void reward(ServerPlayer p,Discipline d,int xp) {long now=LokiData.now(p);if(now-TRAINING.getOrDefault(p.getUUID(),-100L)<20)return;TRAINING.put(p.getUUID(),now);LokiData.train(p,d,xp);if(d==Discipline.TEMPORAL&&LokiData.mastery(p,d)>=800)LokiData.train(p,Discipline.PURPOSE,xp/2);}
    private static void gesture(ServerPlayer p,String animation,String fx,SoundEvent sound) {LokiNetwork.animate(p,animation);LokiNetwork.fx(p,fx);p.level().playSound(null,p.blockPosition(),sound,SoundSource.PLAYERS,.75f,1);}
    private static void notice(ServerPlayer p,String text) {p.displayClientMessage(Component.literal(text),true);}
    public static void clear(ServerPlayer p,boolean death) {release(p,false);clearIllusions(p);TemporalEngine.clear(p);HISTORY.remove(p.getUUID());STRIKES.remove(p.getUUID());INPUT.remove(p.getUUID());TRAINING.remove(p.getUUID());LokiData.clearTransient(p,death);}
    public static void reset() {HISTORY.clear();HELD.clear();CHARMS.clear();STRIKES.clear();INPUT.clear();TRAINING.clear();ILLUSIONS.clear();WATCHED.clear();TemporalEngine.reset();}
}
