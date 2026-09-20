package com.hexgodofstories.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderPlayerEvent;
import org.slf4j.Logger;
import java.util.*;

/**
 * Wearing another creature.
 *
 * <p>Nothing here has a list of supported mobs. A disguise arrives as an entity id plus that
 * creature's own saved state, so the client builds the real thing — whatever mod registered it —
 * loads the snapshot into it so variants, colours, patterns, sizes and carried equipment survive, and
 * then hands it to whichever renderer that mod registered. A GeckoLib creature is drawn by GeckoLib;
 * a vanilla one by vanilla; a creature with a renderer nobody has ever seen by that renderer.
 *
 * <p>The stand-in is never ticked or added to the world. It is driven each frame from the player it
 * stands for — position, the three rotations, pose, walk cycle, swing, hurt flash, fire, invisibility
 * — because that is what every animation system, vanilla or modded, reads to decide what to play. The
 * player's own body is not drawn underneath: the render is replaced, not layered.
 *
 * <p>Every step is guarded. A creature that cannot be built, cannot load its state, or throws from its
 * own renderer is recorded once and that type falls back to the plain player from then on, so an
 * unusual third-party entity degrades the illusion instead of taking down the client.
 */
public final class DisguiseRenderer {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final int MAX_CACHED=24;
    /** Proxy ids live far above anything a server hands out, so modded per-entity caches never collide. */
    private static final int PROXY_BASE=1<<24;

    private record Worn(LivingEntity proxy,int revision,EntityType<?> type) {}
    private static final Map<Integer,Worn> CACHE=new LinkedHashMap<>();
    private static final Set<EntityType<?>> BROKEN=new HashSet<>();
    private static final Map<Integer,Integer> WALKED=new HashMap<>();
    private static boolean rendering;
    private static Object world;

    private DisguiseRenderer() {}

    public static void clear() {CACHE.clear();WALKED.clear();}
    /** A type that has already failed stays failed only for this session, not across a resource reload. */
    public static void forgive() {BROKEN.clear();}

    public static void render(RenderPlayerEvent.Pre event) {
        if(rendering)return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null)return;
        if(world!=mc.level){clear();world=mc.level;}
        Player p=event.getEntity();
        CompoundTag state=ClientState.data(p.getId()).getCompound("disguise");
        if(state.isEmpty()||state.getLong("end")<ClientState.now()) {
            CACHE.remove(p.getId());WALKED.remove(p.getId());
            return;
        }
        // A brief beat of ordinary player before the shape settles, so the change reads as a change.
        if(ClientState.now()-state.getLong("start")<8)return;
        CompoundTag payload=ClientState.DISGUISES.get(p.getId());
        if(payload==null||payload.isEmpty())return;

        LivingEntity proxy;
        EntityRenderer<? super LivingEntity> renderer;
        try {
            proxy=resolve(p,state,payload,mc);
            if(proxy==null)return;
            drive(proxy,p,event.getPartialTick());
            renderer=mc.getEntityRenderDispatcher().getRenderer(proxy);
        } catch(Throwable t) {
            fail(payload.getString("type"),null,t);
            CACHE.remove(p.getId());
            return;
        }
        if(renderer==null)return;

        event.setCanceled(true);
        rendering=true;
        PoseStack pose=event.getPoseStack();
        pose.pushPose();
        try {
            renderer.render(proxy,p.getYRot(),event.getPartialTick(),pose,event.getMultiBufferSource(),event.getPackedLight());
        } catch(Throwable t) {
            fail(payload.getString("type"),proxy.getType(),t);
            CACHE.remove(p.getId());
        } finally {
            pose.popPose();
            rendering=false;
        }
    }

    /** Builds, or reuses, the stand-in for one player's current disguise. */
    private static LivingEntity resolve(Player p,CompoundTag state,CompoundTag payload,Minecraft mc) {
        int revision=state.getInt("revision");
        Worn worn=CACHE.get(p.getId());
        if(worn!=null&&worn.revision==revision&&worn.proxy.level()==mc.level&&!BROKEN.contains(worn.type))return worn.proxy;
        if(worn!=null)CACHE.remove(p.getId());

        LivingEntity built;
        if(payload.getBoolean("player")) {
            var connection=mc.getConnection();
            var info=connection==null||!payload.hasUUID("uuid")?null:connection.getPlayerInfo(payload.getUUID("uuid"));
            if(info==null)return null;
            built=new RemotePlayer(mc.level,info.getProfile());
        } else {
            ResourceLocation key=ResourceLocation.tryParse(payload.getString("type"));
            EntityType<?> type=key==null?null:BuiltInRegistries.ENTITY_TYPE.get(key);
            if(type==null||BROKEN.contains(type))return null;
            Entity created=type.create(mc.level);
            if(!(created instanceof LivingEntity living))return null;
            built=living;
            if(payload.contains("nbt")) {
                // Saved state is the only generic route to a modded creature's chosen appearance; losing
                // it costs the variant, not the disguise, so a failure here is not fatal.
                try {
                    built.load(payload.getCompound("nbt"));
                } catch(Exception e) {
                    report(payload.getString("type"),e);
                }
            }
        }
        built.setId(PROXY_BASE+p.getId());
        built.setNoGravity(true);
        built.setSilent(true);
        if(CACHE.size()>=MAX_CACHED)CACHE.clear();
        CACHE.put(p.getId(),new Worn(built,revision,built.getType()));
        return built;
    }

    /**
     * Maps a player's state onto the borrowed body. Animation systems all read the same handful of
     * fields — where the body is, how fast its limbs are swinging, which way its head is turned, what
     * pose it is in, whether it is swinging or hurt — so filling those in is what makes an arbitrary
     * creature walk, swim, fly, strike and flinch along with the player rather than stand frozen.
     */
    private static void drive(LivingEntity proxy,Player p,float partial) {
        proxy.tickCount=p.tickCount;
        proxy.xOld=p.xOld;proxy.yOld=p.yOld;proxy.zOld=p.zOld;
        proxy.xo=p.xo;proxy.yo=p.yo;proxy.zo=p.zo;
        proxy.setPos(p.getX(),p.getY(),p.getZ());
        proxy.setDeltaMovement(p.getDeltaMovement());
        proxy.setOnGround(p.onGround());
        proxy.fallDistance=p.fallDistance;

        proxy.yRotO=p.yRotO;proxy.setYRot(p.getYRot());
        proxy.xRotO=p.xRotO;proxy.setXRot(p.getXRot());
        proxy.yBodyRotO=p.yBodyRotO;proxy.yBodyRot=p.yBodyRot;
        proxy.yHeadRotO=p.yHeadRotO;proxy.yHeadRot=p.yHeadRot;

        // The walk cycle is advanced once per client tick; a render tick must not push it further or
        // the borrowed creature's legs run at the frame rate.
        Integer last=WALKED.get(p.getId());
        if(last==null||last!=p.tickCount) {
            WALKED.put(p.getId(),p.tickCount);
            proxy.walkAnimation.update(p.walkAnimation.speed(),1);
        }

        proxy.setPose(pose(proxy,p));
        proxy.setSprinting(p.isSprinting());
        proxy.setSwimming(p.isSwimming());
        proxy.setShiftKeyDown(p.isShiftKeyDown());
        proxy.setInvisible(p.isInvisible());
        proxy.setRemainingFireTicks(p.getRemainingFireTicks());
        proxy.setTicksFrozen(p.getTicksFrozen());
        proxy.setAirSupply(p.getAirSupply());

        // hurtTime alone drives the red flash and the flinch; the knock direction behind it is a
        // player-only field, and a borrowed body has no use for it.
        proxy.hurtTime=p.hurtTime;
        proxy.hurtDuration=p.hurtDuration;
        proxy.deathTime=0;
        proxy.attackAnim=p.attackAnim;
        proxy.oAttackAnim=p.oAttackAnim;
        proxy.swinging=p.swinging;
        proxy.swingTime=p.swingTime;
        proxy.swingingArm=p.swingingArm;
        if(proxy.getHealth()<=0)proxy.setHealth(proxy.getMaxHealth());
    }

    /**
     * Player poses do not all exist for a cow. Swimming, flying and sleeping are shared vocabulary and
     * carry across; crouching becomes standing on anything that cannot crouch, which is a graceful
     * fallback rather than a broken model.
     */
    private static Pose pose(LivingEntity proxy,Player p) {
        Pose wanted=p.getPose();
        if(proxy instanceof Player)return wanted;
        return switch(wanted) {
            case SWIMMING,FALL_FLYING,SLEEPING,DYING -> wanted;
            default -> Pose.STANDING;
        };
    }

    private static void report(String type,Throwable t) {
        LOGGER.warn("The borrowed shape could not be worn '{}' fully; falling back for that shape",type,t);
    }
    /**
     * A third-party renderer is arbitrary code, and the point of this system is that an unfamiliar
     * one costs the disguise rather than the session — so anything at all it throws is caught and the
     * shape is retired. A failure of the virtual machine itself is not ours to swallow.
     */
    private static void fail(String type,EntityType<?> shape,Throwable t) {
        if(t instanceof VirtualMachineError error)throw error;
        report(type,t);
        if(shape!=null)BROKEN.add(shape);
    }
}
