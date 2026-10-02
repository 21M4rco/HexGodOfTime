package com.hexgodofstories.server;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.*;
import com.hexgodofstories.data.*;
import com.hexgodofstories.entity.*;
import com.hexgodofstories.network.HexNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

public final class HexServer {
    public static final int CAST=0,ALTERNATE=1,UTILITY=2,TRANSFORM=3,WEAPON=4,SELECT=5,RESYNC=6,SCROLL=7,HOLD_BEGIN=8,HOLD_END=9,ASSIGN=10,FLIGHT=11,TIME=12,BRANCH_TAP=13,WARP_CHOICE=14,WARP_RECALL=15,WARP_STRUGGLE=16,BRANCH_BEGIN=17,BRANCH_END=18,BRANCH_CANCEL=19,
        /** The Scepter's right click: held opens a charge, let go fires it. */
        SCEPTER_PRESS=20,SCEPTER_RELEASE=21,
        /** Anchor Being's held alternate, Gravity Grasp: the alternate key pressed, and let go. */
        GRASP_BEGIN=22,GRASP_END=23,
        /** One of the seven bind slots' keys: choose whatever ability that slot holds. */
        SLOT=24;
    /** Keep rewind's wire value for saved clients; the B/resume action is retired. */
    public static final int TIME_HALT=0,TIME_REWIND=2;
    public record Moment(Vec3 position,float yaw,float pitch,float health) {}
    private record Charm(Mob mob,UUID owner,long end,UUID previous) {}
    private record Strike(int weapon,int combo,long contact,long end) {}
    private static final Map<UUID,ArrayDeque<Moment>> HISTORY=new HashMap<>();
    private static final Map<UUID,Charm> CHARMS=new HashMap<>();
    private static final Map<UUID,Strike> STRIKES=new HashMap<>();
    private static final Map<UUID,Long> INPUT=new HashMap<>(),TRAINING=new HashMap<>();
    private static final Map<UUID,List<UUID>> ILLUSIONS=new HashMap<>();
    private static final Map<UUID,UUID> RIFTS=new HashMap<>();
    private static final Map<UUID,ArrayDeque<Vec3>> WATCHED=new HashMap<>();

    /** Something the caster's spells should strike: a valid target that is not one of the caster's own projections. */
    public static boolean foe(ServerPlayer p,Entity e) {return validTarget(p,e)&&!(e instanceof IllusionEntity copy&&p.getUUID().equals(copy.owner()));}
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
        long now=HexData.now(p);
        if(action==RESYNC){HexNetwork.sync(p);return;}
        // Escaping a trap is available to ordinary players too; the live passage validates it.
        if(action==WARP_STRUGGLE){com.hexgodofstories.warping.WarpCrossing.struggle(p);return;}
        // Letting go is always heard, so a charge can never be left held open by a refused press.
        if(action==SCEPTER_RELEASE){ScepterBlast.release(p);return;}
        if(!HexData.access(p)){notice(p,"Your powers are locked. An operator must use /hgos unlock "+p.getGameProfile().getName()+" on.");return;}
        if(action==WARP_CHOICE){Warping.choose(p,value);return;}
        if(action==SELECT) {
            if(Warping.charging(p))Warping.cancel(p);
            if(now-INPUT.getOrDefault(p.getUUID(),-100L)<2)return;
            INPUT.put(p.getUUID(),now);
            if(value>=0&&value<Ability.values().length&&HexData.unlocked(p,Ability.at(value)))HexData.get(p).putInt("selected",value);
            HexNetwork.sync(p);return;
        }
        // A slot's key. Choosing is free and immediate, and it does not touch the cast throttle: a cast pressed right
        // after it must not be dropped as too soon after the last input.
        if(action==SLOT) {
            int[] slots=HexData.quick(p);
            if(value<0||value>=slots.length)return;
            int id=slots[value];
            if(id<0||id>=Ability.values().length||Ability.at(id).dedicated||!HexData.unlocked(p,Ability.at(id)))return;
            if(HexData.get(p).getInt("selected")==id)return;
            if(Warping.charging(p))Warping.cancel(p);
            HexData.get(p).putInt("selected",id);
            HexNetwork.sync(p);return;
        }
        if(action==ASSIGN) {
            int slot=value/1000,ability=value%1000-1;
            if(ability>=0&&(ability>=Ability.values().length||!HexData.unlocked(p,Ability.at(ability))))return;
            HexData.quick(p,slot,ability);HexNetwork.sync(p);return;
        }
        if(TemporalEngine.frozen(p)||p.isSpectator())return;
        // Being erased is not a state anything is cast out of, and a planted caster has only one move
        // left: letting go. Both refusals sit ahead of every other action on purpose.
        if(Erasure.erasing(p))return;
        if(action==BRANCH_CANCEL){TimeBranch.cancel(p);return;}
        if(action==BRANCH_END){if(TimeBranch.charging(p))TimeBranch.release(p);return;}
        if(action==GRASP_END){GravityGrasp.release(p);return;}
        if(TimeBranch.charging(p)){if(action==UTILITY)TimeBranch.cancel(p);return;}
        // Tapped as fast as a finger allows, so it sits ahead of the shared input throttle; the
        // Scepter's own recovery decides how fast it can actually fire.
        if(action==SCEPTER_PRESS){if(!Arsenal.active(p))ScepterBlast.press(p);return;}
        if(action==SCROLL){Telekinesis.adjust(p,Math.max(-4,Math.min(4,value-8)));return;}
        if(action==HOLD_END){
            if(Arsenal.active(p)){Arsenal.release(p);return;}
            if(Warping.charging(p)){Warping.release(p);return;}
            UUID id=RIFTS.get(p.getUUID());
            if(id!=null&&p.serverLevel().getEntity(id) instanceof RiftEntity rift)rift.releaseCharge();
            Architecture.commit(p);return;
        }
        // Both arms are up and the crown is firing: nothing else is cast until it ends. The utility key lets it go.
        if(Arsenal.active(p)){if(action==UTILITY)Arsenal.release(p);return;}
        // M is independent of the selected spell and must register even immediately after another key.
        if(action==BRANCH_TAP){BranchFist.arm(p);return;}
        if(action==BRANCH_BEGIN){branchBegin(p);return;}
        if(action==GRASP_BEGIN){graspBegin(p);return;}
        if(now-INPUT.getOrDefault(p.getUUID(),-100L)<(TemporalEngine.slowed(p)?15:3))return;
        INPUT.put(p.getUUID(),now);
        // The recall carries no argument at all: the destination is the one already saved on the
        // player, the point is the one the server's own ray finds, and both are read there.
        if(action==WARP_RECALL){Warping.recall(p);return;}
        if(action==UTILITY&&HexData.selected(p)==Ability.WARPING&&Warping.sovereign(p)){Warping.utility(p);return;}
        if(action==UTILITY){Telekinesis.release(p,false);Architecture.forget(p);TemporalEngine.clear(p);dismissRift(p);return;}
        if(action==WEAPON){weapon(p,value!=0);return;}
        if(action==FLIGHT){CosmicFlight.toggle(p);return;}
        if(action==TIME){time(p,value);return;}
        Ability a=action==TRANSFORM?Ability.ASCENSION:HexData.selected(p);
        // Archived selections can still hold a dedicated time ability. Only its own key may fire it.
        if(action!=TRANSFORM&&a.dedicated)return;
        // Returning home must never depend on energy, mastery or the entry spell's recovery. The way
        // out follows whatever the owner's saved mode currently points at.
        if(a==Ability.WARPING&&(action==CAST||action==HOLD_BEGIN)&&Warping.leave(p))return;
        // Telekinesis cast again while holding lets go, whatever its recovery says: letting go is always free.
        if(a==Ability.TELEKINESIS&&action==CAST&&Telekinesis.holding(p)){Telekinesis.release(p,false);HexNetwork.sync(p);return;}
        if(action==ALTERNATE&&secondary(p,a)){HexNetwork.sync(p);return;}
        // Taking the mantle off is always free and always allowed: it drains energy while it is worn, and a
        // wearer too low to pay for a cast, or still inside its recovery, must never be kept in it.
        if(a==Ability.ASCENSION&&(action==TRANSFORM||action==CAST||action==ALTERNATE)&&HexData.get(p).getBoolean("ascended")){
            dismissMantle(p);HexNetwork.sync(p);return;
        }
        if(!HexData.unlocked(p,a)){notice(p,"This chapter of your story is still locked.");return;}
        if(HexData.cooldown(p,a)>0){notice(p,"The spell is recovering.");return;}
        if(HexData.energy(p)<a.cost){notice(p,"Not enough Temporal Energy.");return;}
        if(a==Ability.WARPING){
            // Wide Open: worn, the mantle opens the pool at its full size in one movement.
            if(action==HOLD_BEGIN||action==CAST){if(Transformation.transformed(p))Warping.instant(p);else Warping.begin(p);}
            return;
        }
        // Held or nothing: a plain press never starts the crown, which would then fire itself out unheld.
        if(a==Ability.ARSENAL){if(action==HOLD_BEGIN&&Arsenal.begin(p))HexData.spend(p,a.cost);HexNetwork.sync(p);return;}
        if(action==HOLD_BEGIN) {
            if(!a.hold)return;
            if(Architecture.begin(p))HexData.spend(p,a.cost);
            HexNetwork.sync(p);return;
        }
        // A plain cast press still charges; only a release fires. Deliberately limited to the cast key:
        // any other action starting a charge would plant the caster with nothing to release it but the
        // ten-second limit.
        if(a.hold){Architecture.begin(p);return;}
        if(cast(p,a,action==ALTERNATE)) {
            // Anchor Being cast in the full transformation is its secret, sixty-block variation, and costs two minutes.
            // The mantle's own recovery starts only once it is off (see dismissMantle). Other spells the mantle changes
            // keep their variant's own recovery (Ascended).
            int recovery=a==Ability.THREADS&&HexData.get(p).getBoolean("ascended")?AnchorBeing.GRAND_RECOVERY:a==Ability.ASCENSION?0:a.cooldown;
            if(action!=ALTERNATE&&a!=Ability.ASCENSION&&Transformation.transformed(p)&&Ascended.recovery(a)>=0)recovery=Ascended.recovery(a);
            HexData.spend(p,a.cost);HexData.get(p).putLong("cd_"+a.name(),now+recovery);
            reward(p,a.discipline,90);HexNetwork.sync(p);
        }
    }
    /** Gravity Grasp: Anchor Being chosen and unlocked, and its own recovery run out. It costs nothing else. */
    private static void graspBegin(ServerPlayer p) {
        if(HexData.selected(p)!=Ability.THREADS||GravityGrasp.holding(p))return;
        if(!HexData.unlocked(p,Ability.THREADS)){notice(p,"This chapter of your story is still locked.");return;}
        if(GravityGrasp.recovering(p)){notice(p,"Gravity Grasp is recovering.");return;}
        GravityGrasp.begin(p);
    }
    private static void branchBegin(ServerPlayer p) {
        Ability a=Ability.TIME_BRANCH;
        if(!HexData.unlocked(p,a)){notice(p,"This chapter of your story is still locked.");return;}
        if(HexData.cooldown(p,a)>0){notice(p,"The spell is recovering.");return;}
        if(HexData.energy(p)<a.cost){notice(p,"Not enough Temporal Energy.");return;}
        if(TimeBranch.begin(p))HexData.spend(p,a.cost);
        HexNetwork.sync(p);
    }

    private static boolean secondary(ServerPlayer p,Ability a) {
        if(a==Ability.DUPLICATE){return commandOrDismiss(p);}
        if(a==Ability.ARCHITECTURE){Architecture.dismiss(p);return true;}
        if(a==Ability.MASQUERADE){Masquerade.drop(p);return true;}
        if(a==Ability.TELEKINESIS&&Telekinesis.holding(p)){Telekinesis.release(p,true);return true;}
        if(a==Ability.ENCHANT){direct(p);return true;}
        if(a==Ability.SELECTIVE_STOP&&HexData.unlocked(p,a)){Entity t=target(p,20);if(t!=null){TemporalEngine.exempt(p,t);notice(p,"Your chosen companion may walk through your stopped time.");}return true;}
        if(a==Ability.DAGGERS||a==Ability.TWIN_DAGGERS||a==Ability.LAEVATEINN){dismissWeapons(p);return true;}
        // The ultimate has no alternate action, and says so rather than falling through to the cast path.
        if(a==Ability.TIME_BRANCH){notice(p,"Tap for a charged right fist; hold and release for the torrent.");return true;}
        // Gotcha!: the crown's tap of this key. It keeps its own recovery, apart from the crown's, and asks nothing else of the caster.
        if(a==Ability.ARSENAL){
            if(!HexData.unlocked(p,a)){notice(p,"This chapter of your story is still locked.");return true;}
            if(Arsenal.gotchaRecovering(p)){notice(p,"Gotcha! is recovering.");return true;}
            // Worn, the mantle makes it a swarm of four seeking missiles instead of one gun.
            String refused=Transformation.transformed(p)?Arsenal.swarm(p):Arsenal.gotcha(p);
            if(refused!=null)notice(p,refused);
            return true;
        }
        return false;
    }

    /**
     * The permanent time controls. These are never bound to a slot, so they answer their own keys
     * directly and are reachable the instant they are unlocked, whatever spell is currently selected.
     */
    private static void time(ServerPlayer p,int which) {
        Ability a=switch(which) {
            case TIME_HALT -> Ability.TIME_STOP;
            case TIME_REWIND -> Ability.REWIND;
            default -> null;
        };
        if(a==null)return;
        if(a==Ability.TIME_STOP&&TemporalEngine.owns(p)) {
            TemporalEngine.clear(p);return;
        }
        // Stopping time belongs to the full transformation: it can always be let go, but only begun while transformed.
        if(a==Ability.TIME_STOP&&!Transformation.transformed(p)){notice(p,"Only the full transformation can stop time.");return;}
        if(a==Ability.TIME_STOP&&HexData.energy(p)<5){notice(p,"Five Temporal Energy is needed to hold time.");return;}
        if(!HexData.unlocked(p,a)){notice(p,"This chapter of your story is still locked.");return;}
        if(HexData.cooldown(p,a)>0){notice(p,"The spell is recovering.");return;}
        if(HexData.energy(p)<a.cost){notice(p,"Not enough Temporal Energy.");return;}
        if(!cast(p,a,false))return;
        HexData.spend(p,a.cost);
        HexData.get(p).putLong("cd_"+a.name(),HexData.now(p)+a.cooldown);
        reward(p,a.discipline,90);
        HexNetwork.sync(p);
    }

    private static boolean cast(ServerPlayer p,Ability a,boolean secondary) {
        Entity t=target(p,24);Vec3 look=p.getLookAngle();long now=HexData.now(p);
        // Worn, the mantle makes most spells something bigger (Glorious); a null answer is the plain spell.
        if(!secondary&&Transformation.transformed(p)){Boolean done=Glorious.cast(p,a,t);if(done!=null)return done;}
        switch(a) {
            case DUPLICATE -> {return duplicate(p,false,false);}
            case MIRAGE -> {if(!duplicate(p,true,false))return false;HexData.get(p).putLong("vanishUntil",now+50);p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY,50,0,false,false));return true;}
            case PROJECTION_SWAP -> {if(secondary)return duplicate(p,false,true);return swap(p);}
            case MASQUERADE -> {
                if(t==null){notice(p,"Look at a creature to borrow its shape.");return false;}
                if(!(t instanceof LivingEntity)){notice(p,"Only a living shape can be worn.");return false;}
                if(!Masquerade.assume(p,t)){notice(p,"That shape refuses to be read.");return false;}
                // Impostor: worn, the mantle leaves the one copied the stranger, and everything near it turns on it.
                if(Transformation.transformed(p)&&t instanceof LivingEntity victim&&validTarget(p,victim))Glorious.impostor(p,victim);
                gesture(p,"illusion","disguise",HexGodOfStories.ILLUSION_SOUND.get());return true;
            }
            case RIFT -> {return false;} // legacy tombstone; Fracture moved into Warping
            case BOLT -> {SpellProjectile.cast(p,p.getEyePosition().add(look.scale(.5)),look,secondary?1:0,false);gesture(p,"bolt","cast",HexGodOfStories.SORCERY.get());return true;}
            case PUSH -> {for(Entity e:p.level().getEntities(p,p.getBoundingBox().inflate(5),e->validTarget(p,e))){Vec3 away=e.position().subtract(p.position()).normalize();e.setDeltaMovement(away.scale(1.1).add(0,.25,0));e.hurtMarked=true;}gesture(p,"push","push",HexGodOfStories.SORCERY.get());return true;}
            case BLINK -> {Vec3 destination=safeAim(p,8+HexData.mastery(p,Discipline.SORCERY)/90.0);if(destination==null)return false;gesture(p,"blink","depart",HexGodOfStories.TELEPORT.get());teleport(p,destination);HexNetwork.arrival(p);return true;}
            case WARD -> {HexData.get(p).putLong("wardUntil",now+100);gesture(p,"ward","ward",HexGodOfStories.SORCERY.get());return true;}
            case TELEKINESIS -> {
                if(!Telekinesis.grab(p,t))return false;
                gesture(p,"telekinesis","hold",HexGodOfStories.SORCERY.get());return true;
            }
            case DAGGERS,TWIN_DAGGERS,LAEVATEINN -> {return conjure(p,a);}
            case ENCHANT -> {
                if(t instanceof ServerPlayer other&&validTarget(p,t)){other.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.CONFUSION,50,0,false,false));HexNetwork.fx(other,"enchant");gesture(p,"enchant","cast",HexGodOfStories.SORCERY.get());return true;}
                if(!(t instanceof Mob mob)||!validTarget(p,mob)||mob.getMaxHealth()>30+HexData.mastery(p,Discipline.ENCHANTMENT)*.25)return false;
                charm(p,mob,240+HexData.mastery(p,Discipline.ENCHANTMENT)/2);
                gesture(p,"enchant","enchant",HexGodOfStories.ILLUSION_SOUND.get());return true;
            }
            case MEMORY -> {
                if(t==null||!memoryTrail(p,t))return false;
                gesture(p,"enchant","memory",HexGodOfStories.ILLUSION_SOUND.get());return true;
            }
            case REWIND -> {
                if(!PersonalRewind.rewind(p)){notice(p,"Ten safe seconds of personal history and an unchanged inventory (apart from eaten food) are required.");return false;}
                gesture(p,"time_slip","slip",HexGodOfStories.SLIP.get());return true;
            }
            case TIME_SLIP -> {
                ArrayDeque<Moment> h=HISTORY.get(p.getUUID());if(h==null||h.size()<10)return false;
                List<Moment> history=new ArrayList<>(h);int mastery=HexData.mastery(p,Discipline.TEMPORAL);
                int index=mastery<160?p.getRandom().nextInt(Math.max(1,history.size()-6)):Math.max(0,history.size()-16);
                Moment m=history.get(index);if(!safe(p,m.position))return false;
                gesture(p,"time_slip","slip",HexGodOfStories.SLIP.get());teleport(p,m.position);p.setYRot(m.yaw);p.setXRot(m.pitch);
                HexNetwork.fx(p,"slip");return true;
            }
            case TIME_STOP -> {if(!TemporalEngine.beginStop(p))return false;return true;}
            case SELECTIVE_STOP -> {if(t==null||!validTarget(p,t)||!TemporalEngine.field(p,true,t,t instanceof Player?40:100))return false;gesture(p,"time_stop","bind",HexGodOfStories.STOP.get());return true;}
            // Anchor Being. Silent on purpose: no gesture, no sound, nothing that says a swap just happened.
            case THREADS -> {if(secondary){notice(p,"Hold the alternate key for Gravity Grasp.");return false;}return AnchorBeing.cast(p);}
            // Worn, it is taken off before any cast is paid for (see the action handler); never charged for here.
            case ASCENSION -> {if(HexData.get(p).getBoolean("ascended")){dismissMantle(p);HexNetwork.sync(p);return false;}HexData.get(p).putBoolean("ascended",true);HexData.get(p).putLong("transformStart",now);HexData.energy(p,HexData.maxEnergy(p));Transformation.sustain(p);HexNetwork.fx(p,"ascend");p.level().playSound(null,p.blockPosition(),HexGodOfStories.ASCEND.get(),SoundSource.PLAYERS,.75f,1);return true;}
            default -> {return false;}
        }
    }

    /**
     * Takes the mantle off: its flight, the branch, a stop of time it was holding and everything it granted go with it,
     * and its five minutes' recovery starts, whether it was taken off or worn down to nothing.
     */
    static void dismissMantle(ServerPlayer p) {
        HexData.get(p).putBoolean("ascended",false);HexData.get(p).putLong("transformStart",HexData.now(p));
        HexData.get(p).putLong("cd_"+Ability.ASCENSION.name(),HexData.now(p)+Ability.ASCENSION.cooldown);
        CosmicFlight.revoke(p);TimeBranch.cancel(p);Transformation.strip(p);
        if(TemporalEngine.stopping(p))TemporalEngine.clear(p);
        HexNetwork.fx(p,"dismiss");p.level().playSound(null,p.blockPosition(),HexGodOfStories.ASCEND.get(),SoundSource.PLAYERS,.75f,1);
    }

    /** The pull mode: a doorway on a tap, a five-block vacuum when the key is held. */
    public static boolean pullFracture(ServerPlayer p,int stage) {
        if(stage==RiftEntity.RELEASE)return false;
        if(!fracture(p,null))return false;
        if(stage==RiftEntity.HOLD)armFracture(p);
        return true;
    }
    /** A travel mode: the same break, but pointed somewhere the owner chose. */
    public static boolean openFracture(ServerPlayer p,FractureAnchor destination) {
        return destination!=null&&fracture(p,destination);
    }

    /** Cracks the air ahead, or — when already inside the sanctum — opens the way back out. */
    private static boolean fracture(ServerPlayer p,FractureAnchor destination) {
        // A crossing puts the terrain screen up under a held cast key; whatever input that shakes loose
        // arrives a tick or two after the arrival. Nothing opens a break in that window.
        if(HexData.get(p).getLong("breakGuard")>p.server.overworld().getGameTime())return false;
        dismissRift(p);
        boolean homeward=PocketRealm.inside(p.level());
        // Place a walk-through door at feet height, not ten blocks away at the eye-ray's height.
        Vec3 forward=new Vec3(p.getLookAngle().x,0,p.getLookAngle().z).normalize();
        if(forward.lengthSqr()<.01)forward=new Vec3(0,0,1);
        Vec3 spot=null;
        for(double distance=3;distance>=1;distance-=.5) {
            Vec3 candidate=p.position().add(forward.scale(distance));
            if(safe(p,candidate)){spot=candidate;break;}
        }
        if(spot==null&&homeward)spot=p.position();
        if(spot==null){notice(p,"There is no room here for the break to open.");return false;}
        RiftEntity rift=RiftEntity.open(p,spot,homeward,destination);
        RIFTS.put(p.getUUID(),rift.getUUID());
        // RiftEntity owns the positional crack sound; playing it on the caster doubled the attack.
        HexNetwork.animate(p,"threads");
        return true;
    }
    private static void armFracture(ServerPlayer p) {
        UUID id=RIFTS.get(p.getUUID());
        if(id!=null&&p.serverLevel().getEntity(id) instanceof RiftEntity rift)rift.armCharge();
    }
    /** Shuts a caster's break wherever it hangs, and forgets it. */
    public static boolean closeRift(ServerPlayer p) {return dismissRift(p);}
    private static boolean dismissRift(ServerPlayer p) {
        UUID id=RIFTS.remove(p.getUUID());
        if(id==null)return false;
        // Looked up in the caster's *current* level, a break was never found again once they had walked
        // through it — which is exactly how a portal was left standing after its owner had arrived.
        for(ServerLevel level:p.server.getAllLevels())
            if(level.getEntity(id) instanceof RiftEntity rift){rift.close();return true;}
        return false;
    }

    /** Secondary on Living Projection: aim at a foe to set every decoy on it, aim at nothing to dismiss them all. */
    private static boolean commandOrDismiss(ServerPlayer p) {
        Entity aimed=target(p,26);
        List<IllusionEntity> mine=illusions(p);
        if(aimed instanceof LivingEntity quarry&&validTarget(p,quarry)&&!mine.isEmpty()) {
            mine.forEach(e->e.command(quarry));
            HexNetwork.fx(quarry,"marked");
            gesture(p,"illusion","command",HexGodOfStories.ILLUSION_SOUND.get());
            notice(p,mine.size()+(mine.size()==1?" projection answers.":" projections answer."));
            return true;
        }
        clearIllusions(p);return true;
    }
    /** Decoys already in the field adopt whatever their caster decides to fight. */
    public static void engaged(ServerPlayer p,LivingEntity victim) {
        List<UUID> ids=ILLUSIONS.get(p.getUUID());
        if(ids==null||ids.isEmpty()||!validTarget(p,victim))return;
        for(IllusionEntity e:illusions(p))if(!e.aggressive())e.command(victim);
    }

    private static boolean conjure(ServerPlayer p,Ability a) {
        // Calling the Scepter already carried trades places with whatever is in hand, so it loses nothing
        // and needs no free hand; only forming a new weapon does.
        boolean calling=a==Ability.LAEVATEINN&&!ConjuredWeapon.scepter(p).isEmpty();
        if(!calling&&!p.getMainHandItem().isEmpty()&&!(p.getMainHandItem().getItem() instanceof ConjuredWeapon)){notice(p,"Free your main hand to conjure.");return false;}
        if(a==Ability.TWIN_DAGGERS&&!p.getOffhandItem().isEmpty()&&!(p.getOffhandItem().getItem() instanceof ConjuredWeapon)){notice(p,"Free your other hand for twin daggers.");return false;}
        ItemStack item=a==Ability.LAEVATEINN?summonScepter(p):new ItemStack(HexGodOfStories.DAGGER.get());
        item.getOrCreateTag().putUUID("conjurer",p.getUUID());item.getOrCreateTag().putLong("formed",HexData.now(p));
        p.setItemInHand(InteractionHand.MAIN_HAND,item);
        if(a==Ability.TWIN_DAGGERS) {
            ItemStack off=item.copy();off.getOrCreateTag().putBoolean("reverse",true);
            p.setItemInHand(InteractionHand.OFF_HAND,off);
        }
        gesture(p,a==Ability.LAEVATEINN?"scepter_manifest":"conjure","conjure",HexGodOfStories.CONJURE.get());return true;
    }
    /**
     * There is only ever one Scepter. Casting it again calls the one already carried, wherever it is in
     * the inventory, into the selected slot (it forms there afresh) and lets any stray copy go; only a
     * caster carrying none is given a new one. An ordinary item in hand moves to where the Scepter was;
     * a conjured one in hand is dismissed, as it always was.
     */
    private static ItemStack summonScepter(ServerPlayer p) {
        ItemStack kept=ConjuredWeapon.scepter(p),hand=p.getMainHandItem();
        Inventory inventory=p.getInventory();
        int from=-1;
        for(int i=0;i<inventory.getContainerSize();i++) {
            ItemStack stack=inventory.getItem(i);
            if(!ConjuredWeapon.scepterOf(stack,p))continue;
            if(stack==kept)from=i;
            inventory.setItem(i,ItemStack.EMPTY);
        }
        if(kept.isEmpty())return new ItemStack(HexGodOfStories.SCEPTER.get());
        if(from>=0&&from!=inventory.selected&&!hand.isEmpty()&&!(hand.getItem() instanceof ConjuredWeapon))inventory.setItem(from,hand);
        return kept;
    }
    private static void dismissWeapons(ServerPlayer p) {
        for(InteractionHand hand:InteractionHand.values()) {
            ItemStack held=p.getItemInHand(hand);
            if(held.getItem() instanceof ConjuredWeapon&&held.hasTag()&&held.getTag().hasUUID("conjurer"))p.setItemInHand(hand,ItemStack.EMPTY);
        }
    }

    public static void weapon(ServerPlayer p,boolean secondary) {
        InteractionHand hand=secondary&&p.getMainHandItem().isEmpty()&&p.getOffhandItem().is(HexGodOfStories.DAGGER.get())
            ?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        weapon(p,secondary,hand);
    }
    public static void weapon(ServerPlayer p,boolean secondary,InteractionHand hand) {
        ItemStack held=p.getItemInHand(hand);
        if(!HexData.access(p)||TemporalEngine.frozen(p)||!p.isAlive()||p.isSpectator()||!(held.getItem() instanceof ConjuredWeapon w))return;
        if(hand==InteractionHand.OFF_HAND&&(!secondary||w.kind!=0))return;
        if(!ConjuredWeapon.belongsTo(held,p)){p.setItemInHand(hand,ItemStack.EMPTY);return;}
        // The Scepter's right click is a hold, carried by SCEPTER_PRESS and SCEPTER_RELEASE.
        if(w.kind==1)return;
        long now=HexData.now(p);Strike prior=STRIKES.get(p.getUUID());
        if(prior!=null&&prior.end>now)return;
        int combo=prior==null||now-prior.end>18?0:(prior.combo+1)%4;
        if(secondary&&w.kind==0) {
            ThrownDagger.throwFrom(p,p.getEyePosition().add(p.getLookAngle().scale(.4)),p.getLookAngle(),false);
            held.shrink(1);
            p.containerMenu.broadcastChanges();
            HexNetwork.animate(p,"dagger_throw");HexNetwork.fx(p,"throw");
            STRIKES.put(p.getUUID(),new Strike(0,combo,0,now+13));return;
        }
        if(secondary&&w.kind==2) {
            Entity t=target(p,4);
            if(t!=null&&validTarget(p,t))TemporalEngine.field(p,true,t,30);
            gesture(p,"time_stop","bind",HexGodOfStories.STOP.get());
            STRIKES.put(p.getUUID(),new Strike(2,combo,0,now+40));return;
        }
        int windup=4,recovery=10;
        boolean twin=p.getOffhandItem().is(HexGodOfStories.DAGGER.get());
        STRIKES.put(p.getUUID(),new Strike(w.kind,combo,now+windup,now+recovery));
        HexNetwork.animate(p,(twin?"twin_":"dagger_")+combo);
        HexNetwork.fx(p,"slash");
        p.level().playSound(null,p.blockPosition(),HexGodOfStories.BLADE_SWING.get(),SoundSource.PLAYERS,.85f,1.08f+combo*.04f);
    }

    public static void tick(ServerPlayer p) {
        long now=HexData.now(p);CompoundTag d=HexData.get(p);
        if(!p.isAlive()){ScepterBlast.cancel(p);Arsenal.forget(p);GravityGrasp.forget(p);Transformation.strip(p);return;}
        if(!HexData.access(p)){ScepterBlast.cancel(p);Arsenal.forget(p);GravityGrasp.forget(p);Transformation.strip(p);CosmicFlight.revoke(p);dismissWeapons(p);return;}
        PersonalRewind.record(p);
        ScepterBlast.tick(p);
        Arsenal.tick(p);
        GravityGrasp.tick(p);
        // The mantle is armour, so it is maintained where the mantle is: every tick, granted and
        // renewed while it is worn and taken off the instant it is not.
        Transformation.sustain(p);
        CosmicFlight.tick(p);
        TimeBranch.tick(p);
        BranchFist.tick(p);
        if(now%4==0&&!TemporalEngine.frozen(p)) {
            ArrayDeque<Moment> h=HISTORY.computeIfAbsent(p.getUUID(),k->new ArrayDeque<>());
            h.addLast(new Moment(p.position(),p.getYRot(),p.getXRot(),p.getHealth()));
            while(h.size()>50)h.removeFirst();
        }
        int temporal=HexData.mastery(p,Discipline.TEMPORAL);
        if(now%200==0&&temporal<160&&HexData.unlocked(p,Ability.TIME_SLIP)&&!TemporalEngine.frozen(p)&&!p.isPassenger()&&p.getRandom().nextInt(5)==0&&HexData.energy(p)>=15&&HexData.cooldown(p,Ability.TIME_SLIP)==0) {
            if(cast(p,Ability.TIME_SLIP,false)){HexData.spend(p,15);d.putLong("cd_TIME_SLIP",now+400);reward(p,Discipline.TEMPORAL,120);}
        }
        if(now%20==0){
            if(d.getBoolean("ascended")) {
                // The mantle is paid for as it is worn, and nothing comes back while it is on. Worn to nothing, it falls away.
                float left=HexData.energy(p)-(d.getBoolean("cosmicFlying")?Transformation.FLYING_DRAIN:Transformation.DRAIN);
                HexData.energy(p,left);
                if(left<=0){notice(p,"Your Temporal Energy is spent, and the mantle falls away.");dismissMantle(p);}
            } else if(!TemporalEngine.sustaining(p))HexData.energy(p,HexData.energy(p)+2);
            HexNetwork.sync(p);
        }
        if(d.contains("disguise")&&d.getCompound("disguise").getLong("end")<now){Masquerade.drop(p);HexNetwork.sync(p);}
        if(now%5==0) {
            for(LivingEntity e:p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(12),LivingEntity::isAlive)) {
                if(WATCHED.size()>256&&!WATCHED.containsKey(e.getUUID()))break;
                ArrayDeque<Vec3> q=WATCHED.computeIfAbsent(e.getUUID(),k->new ArrayDeque<>());
                q.addLast(e.position());while(q.size()>24)q.removeFirst();
            }
        }
        Telekinesis.tick(p);
        Architecture.tick(p);
        Strike strike=STRIKES.get(p.getUUID());
        if(strike!=null&&strike.contact==now) {
            double reach=2.8;
            boolean finisher=strike.combo==3;
            for(LivingEntity e:p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(reach),e->validTarget(p,e)&&p.hasLineOfSight(e))) {
                Vec3 direction=e.getEyePosition().subtract(p.getEyePosition()).normalize();
                if(direction.dot(p.getLookAngle())<.35||p.distanceToSqr(e)>reach*reach)continue;
                if(!e.hurt(p.damageSources().playerAttack(p),4))continue;
                e.knockback(.25,p.getX()-e.getX(),p.getZ()-e.getZ());
                if(finisher)Bleed.apply(p,e,1,120);
                reward(p,Discipline.CONJURATION,55);
                HexNetwork.fx(e,"impact");
                p.level().playSound(null,e.blockPosition(),HexGodOfStories.BLADE_HIT.get(),SoundSource.PLAYERS,.85f,1+p.getRandom().nextFloat()*.14f);
            }
        }
    }

    public static void tickLevel(ServerLevel level) {
        long now=level.getGameTime();
        Iterator<Charm> it=CHARMS.values().iterator();
        while(it.hasNext()) {
            Charm c=it.next();
            if(c.mob.level()!=level)continue;
            ServerPlayer owner=level.getServer().getPlayerList().getPlayer(c.owner);
            if(c.end<now||owner==null||owner.level()!=level||!c.mob.isAlive()) {
                if(c.previous!=null&&level.getEntity(c.previous) instanceof LivingEntity old)c.mob.setTarget(old);
                it.remove();continue;
            }
            if(now%10==0){if(c.mob.getTarget()==owner)c.mob.setTarget(null);if(c.mob.getTarget()==null&&c.mob.distanceToSqr(owner)>9)c.mob.getNavigation().moveTo(owner,1.05);}
        }
        Bleed.tick(level);
        Delusion.tick(level);
        Frostbite.tick(level);
        ScepterBlast.tick(level);
        Arsenal.tickLevel(level);
        Threat.tick(now);
        Decoy.tick(now);
        Telekinesis.tickSlams(level);
        TimeBranch.tickLevel(level);
        Erasure.tickLevel(level);
        Nothingness.tick(level);
        IllusoryWalls.tick(level);
        PocketRealm.tick(level);
        WarpEmergence.tick(level);
        Warping.tick(level);WarpRealms.tick(level);
        if(now%200==0)WATCHED.entrySet().removeIf(e->level.getEntity(e.getKey())==null);
    }

    /** A creature charmed into following the caster for {@code ticks}, its old quarry remembered for when it ends. */
    static void charm(ServerPlayer p,Mob mob,int ticks) {
        CHARMS.put(mob.getUUID(),new Charm(mob,p.getUUID(),HexData.now(p)+ticks,mob.getTarget()==null?null:mob.getTarget().getUUID()));
        mob.setTarget(null);HexNetwork.fx(mob,"enchant");
    }
    static boolean charmed(Mob mob) {return CHARMS.containsKey(mob.getUUID());}
    /** Memory Echo's trail of a body's recent steps, for the caster's eyes only. False when none has gathered yet. */
    static boolean memoryTrail(ServerPlayer p,Entity t) {
        ArrayDeque<Vec3> path=WATCHED.get(t.getUUID());
        if(path==null||path.isEmpty()){notice(p,"Stay near the target while its memory gathers.");return false;}
        CompoundTag n=new CompoundTag();int i=0;
        for(Vec3 v:path){CompoundTag point=new CompoundTag();point.putDouble("x",v.x);point.putDouble("y",v.y);point.putDouble("z",v.z);n.put("p"+i++,point);}
        n.putInt("count",i);HexNetwork.tracking(p,new HexNetwork.Message(HexNetwork.MEMORY,t.getId(),n));
        return true;
    }
    /**
     * Slipstream (Time Slip in the full transformation): the caster slips back exactly three seconds, whatever their
     * mastery, and every creature or player within ten blocks is dragged back three seconds along its own path.
     */
    static boolean slipstream(ServerPlayer p) {
        ArrayDeque<Moment> h=HISTORY.get(p.getUUID());if(h==null||h.size()<16)return false;
        List<Moment> history=new ArrayList<>(h);
        Moment m=history.get(history.size()-15);if(!safe(p,m.position))return false;
        // Everyone near is taken first, from where they stand now, before the caster has moved away from them.
        List<LivingEntity> near=p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(10),e->foe(p,e)&&!Glorious.huge(e)&&!e.isPassenger());
        gesture(p,"time_slip","slip",HexGodOfStories.SLIP.get());teleport(p,m.position);p.setYRot(m.yaw);p.setXRot(m.pitch);
        HexNetwork.fx(p,"slip");
        for(LivingEntity e:near) {
            ArrayDeque<Vec3> path=WATCHED.get(e.getUUID());
            if(path==null||path.size()<2)continue;
            List<Vec3> steps=new ArrayList<>(path);
            Vec3 back=steps.get(Math.max(0,steps.size()-12));
            if(!e.level().noCollision(e,e.getBoundingBox().move(back.subtract(e.position()))))continue;
            HexNetwork.fx(e,"slip");
            if(e instanceof ServerPlayer q){q.stopRiding();q.connection.teleport(back.x,back.y,back.z,q.getYRot(),q.getXRot());}
            else e.teleportTo(back.x,back.y,back.z);
            e.setDeltaMovement(Vec3.ZERO);e.hurtMarked=true;e.fallDistance=0;
            e.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,20,1,false,false));
        }
        return true;
    }

    public static boolean charmedAgainst(Mob mob,LivingEntity target) {Charm c=CHARMS.get(mob.getUUID());return c!=null&&target!=null&&target.getUUID().equals(c.owner);}
    private static void direct(ServerPlayer p) {
        Entity t=target(p,24);
        Vec3 point=p.level().clip(new ClipContext(p.getEyePosition(),p.getEyePosition().add(p.getLookAngle().scale(24)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getLocation();
        for(Charm c:CHARMS.values()) {
            if(!c.owner.equals(p.getUUID()))continue;
            if(t instanceof LivingEntity l&&validTarget(p,l)&&l!=c.mob)c.mob.setTarget(l);
            else {c.mob.setTarget(null);c.mob.getNavigation().moveTo(point.x,point.y,point.z,1.1);}
        }
    }

    /** Cheap enough to ask on every target change: a map lookup, never a world query. */
    public static boolean hasProjections(UUID owner) {
        List<UUID> ids=ILLUSIONS.get(owner);
        return ids!=null&&!ids.isEmpty();
    }
    public static List<IllusionEntity> projections(ServerLevel level,UUID owner) {
        List<UUID> ids=ILLUSIONS.get(owner);
        if(ids==null||ids.isEmpty())return List.of();
        List<IllusionEntity> live=new ArrayList<>(ids.size());
        for(UUID id:ids)if(level.getEntity(id) instanceof IllusionEntity e&&e.isAlive())live.add(e);
        return live;
    }
    /** What the caster is holding right now, or -1 when their hands are empty. */
    private static int heldLoadout(ServerPlayer p) {
        if(!(p.getMainHandItem().getItem() instanceof ConjuredWeapon w))return -1;
        if(w.kind==1)return IllusionEntity.SWORD;
        return p.getOffhandItem().is(HexGodOfStories.DAGGER.get())?IllusionEntity.TWIN:IllusionEntity.DAGGER;
    }

    private static List<IllusionEntity> illusions(ServerPlayer p) {
        List<UUID> ids=ILLUSIONS.computeIfAbsent(p.getUUID(),k->new ArrayList<>());
        ids.removeIf(id->!(p.serverLevel().getEntity(id) instanceof IllusionEntity));
        List<IllusionEntity> list=new ArrayList<>();
        for(UUID id:ids)list.add((IllusionEntity)p.serverLevel().getEntity(id));
        return list;
    }
    static boolean duplicate(ServerPlayer p,boolean multiple,boolean aimed) {
        // Living Legion: worn, the mantle makes a plain projection two at a time, two past the usual limit.
        boolean legion=!multiple&&!aimed&&Transformation.transformed(p);
        int max=1+HexData.mastery(p,Discipline.MISCHIEF)/180+(legion?2:0);
        List<IllusionEntity> existing=illusions(p);
        if(existing.size()>=max){notice(p,"Your projections are already at capacity.");return false;}
        int count=multiple?max-existing.size():legion?Math.min(2,max-existing.size()):1,spawned=0,mirrored=heldLoadout(p);
        for(int i=0;i<count;i++) {
            double angle=p.getYRot()*Math.PI/180+i*Math.PI*2/count;
            Vec3 pos=aimed?safeAim(p,12):p.position().add(Math.cos(angle)*2,0,Math.sin(angle)*2);
            if(pos==null||!safe(p,pos))continue;
            IllusionEntity e=new IllusionEntity(HexGodOfStories.ILLUSION.get(),p.level());
            e.setPos(pos);
            // The caster's own arms go to the first copy, so the real body is never the odd one out;
            // the rest cycle through the armoury so a court reads as a company rather than a print run.
            int loadout=spawned==0&&mirrored>=0?mirrored:(existing.size()+i+p.getId())%3;
            e.configure(p,new IllusionEntity.Spec(240+HexData.mastery(p,Discipline.MISCHIEF)/2,IllusionEntity.Behavior.values()[(i+existing.size()+p.getRandom().nextInt(7))%7],true,false,true),loadout);
            p.level().addFreshEntity(e);ILLUSIONS.get(p.getUUID()).add(e.getUUID());spawned++;
        }
        if(spawned>0) {
            gesture(p,"illusion","cast",HexGodOfStories.ILLUSION_SOUND.get());
            // Whatever was already hunting the caster now has a choice to make.
            Decoy.scatter(p);
        }
        return spawned>0;
    }
    private static boolean swap(ServerPlayer p) {
        IllusionEntity e=illusions(p).stream().filter(q->p.distanceToSqr(q)<900&&safe(p,q.position())).min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
        if(e==null)return false;
        Vec3 old=p.position();
        gesture(p,"blink","depart",HexGodOfStories.TELEPORT.get());
        teleport(p,e.position());e.setPos(old);
        HexNetwork.arrival(p);return true;
    }
    public static void clearIllusions(ServerPlayer p) {illusions(p).forEach(IllusionEntity::dispel);ILLUSIONS.remove(p.getUUID());}

    public static boolean safe(ServerPlayer p,Vec3 pos) {
        return p.level().hasChunkAt(BlockPos.containing(pos))&&p.level().getWorldBorder().isWithinBounds(BlockPos.containing(pos))
            &&p.level().noCollision(p,p.getBoundingBox().move(pos.subtract(p.position())))
            &&!p.level().containsAnyLiquid(p.getBoundingBox().move(pos.subtract(p.position())));
    }
    static Vec3 safeAim(ServerPlayer p,double range) {
        Vec3 from=p.getEyePosition();
        BlockHitResult hit=p.level().clip(new ClipContext(from,from.add(p.getLookAngle().scale(range)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        Vec3 goal=hit.getLocation().subtract(p.getLookAngle().scale(.8)).add(0,-.5,0);
        for(int i=0;i<8;i++){Vec3 v=goal.add(0,i*.5,0);if(safe(p,v))return v;}
        return null;
    }
    static void teleport(ServerPlayer p,Vec3 v) {
        Telekinesis.release(p,false);p.stopRiding();
        p.connection.teleport(v.x,v.y,v.z,p.getYRot(),p.getXRot());
        p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
    }
    public static void reward(ServerPlayer p,Discipline d,int xp) {
        long now=HexData.now(p);
        if(now-TRAINING.getOrDefault(p.getUUID(),-100L)<20)return;
        TRAINING.put(p.getUUID(),now);HexData.train(p,d,xp);
        if(d==Discipline.TEMPORAL&&HexData.mastery(p,d)>=800)HexData.train(p,Discipline.PURPOSE,xp/2);
    }
    /** Flourishes the copies share, so a burst of sorcery never singles out the body that cast it. */
    private static final Set<String> MIRRORED=Set.of("cast","conjure","slash","throw","ward","push","hold","disguise");
    static void gesture(ServerPlayer p,String animation,String fx,SoundEvent sound) {
        HexNetwork.animate(p,animation);HexNetwork.fx(p,fx);
        if(MIRRORED.contains(fx))for(IllusionEntity e:illusions(p))HexNetwork.fx(e,fx);
        p.level().playSound(null,p.blockPosition(),sound,SoundSource.PLAYERS,.75f,1);
    }
    static void notice(ServerPlayer p,String text) {p.displayClientMessage(Component.literal(text),true);}

    /** Persisted administrative switch. Turning it off immediately tears down player-owned power state. */
    public static void access(ServerPlayer p,boolean enabled) {
        if(!enabled&&Warping.sovereign(p))Warping.leave(p);
        HexData.access(p,enabled);
        if(!enabled) {
            HexData.get(p).putBoolean("ascended",false);
            dismissWeapons(p);
            clear(p,false);
            Iterator<Map.Entry<UUID,Charm>> charms=CHARMS.entrySet().iterator();
            while(charms.hasNext()) {
                Charm charm=charms.next().getValue();
                if(!charm.owner.equals(p.getUUID()))continue;
                if(charm.mob.isAlive())charm.mob.setTarget(null);
                charms.remove();
            }
            if(PocketRealm.inside(p.level()))PocketRealm.leave(p);
        }
        HexNetwork.sync(p);
    }

    public static void clear(ServerPlayer p,boolean death) {
        Warping.cancel(p);
        com.hexgodofstories.warping.WarpCrossing.forget(p);
        com.hexgodofstories.warping.WarpEmergence.cancel(p);
        Telekinesis.forget(p);Architecture.dismiss(p);clearIllusions(p);dismissRift(p);TemporalEngine.clear(p);
        Masquerade.drop(p);Threat.forget(p);
        CosmicFlight.revoke(p);
        // The charge, the erasure hold and the granted armour all go together; none of them may outlive
        // a death, a logout or a crossing.
        TimeBranch.forget(p);BranchFist.clear(p);Erasure.forget(p);Transformation.strip(p);
        Arsenal.forget(p);
        GravityGrasp.forget(p);
        Glorious.forget(p);
        HISTORY.remove(p.getUUID());STRIKES.remove(p.getUUID());ScepterBlast.forget(p);INPUT.remove(p.getUUID());TRAINING.remove(p.getUUID());
        HexData.clearTransient(p,death);
    }
    public static void reset() {
        PersonalRewind.reset();
        HISTORY.clear();CHARMS.clear();STRIKES.clear();INPUT.clear();TRAINING.clear();ILLUSIONS.clear();WATCHED.clear();RIFTS.clear();
        Warping.reset();
        Telekinesis.reset();Architecture.reset();Bleed.reset();Frostbite.reset();ScepterBlast.reset();PocketRealm.reset();TemporalEngine.reset();
        Threat.reset();Decoy.reset();TimeBranch.reset();Erasure.reset();Starfall.reset();Arsenal.reset();GravityGrasp.reset();
        Delusion.reset();Glorious.reset();
    }
}
