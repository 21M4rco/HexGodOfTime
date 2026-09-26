package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.ScepterPose;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.util.ArrayList;
import java.util.List;

/**
 * The Scepter's light: real Photon emitters, rendered and ticked by Photon's particle engine.
 *
 * <p>Every shot leaves the stone. For the caster in first person that means the stone as it is drawn
 * in the hand, which lives in the hand's own 70 degree projection; it is carried into the world's
 * projection here so the beam starts exactly on it at any field of view. Everyone else sees it leave
 * the stone of the caster's third-person staff, where the server put it.
 */
public final class ScepterFx {
    private ScepterFx() { }
    private static final List<Emitter> ACTIVE=new ArrayList<>();
    private static final ResourceLocation FLARE=new ResourceLocation("hexgodofstories","textures/particle/scepter_flare.png");
    private static final ResourceLocation BEAM=new ResourceLocation("hexgodofstories","textures/particle/scepter_beam.png");
    /** The stone in the raised first-person pose, in hand view space; see WeaponRenderer's AIM. */
    private static final float STONE_X=0.224880f,STONE_Y=-0.256646f,STONE_Z=-1.258326f;

    public static void clear(){for(Emitter e:ACTIVE)e.remove(true);ACTIVE.clear();}

    private static void register(Emitter emitter,Vec3 at) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null)return;
        ACTIVE.removeIf(e->!e.isAlive());
        while(ACTIVE.size()>=96)ACTIVE.remove(0).remove(true);
        emitter.setLevel(mc.level);
        emitter.setPos(at.x,at.y,at.z);
        ACTIVE.add(emitter);
        mc.particleEngine.add(emitter);
    }

    private static void material(MaterialSetting material,RendererSetting renderer,ResourceLocation texture) {
        material.setMaterial(new TextureMaterial(texture));
        material.setCull(false);
        material.setDepthTest(true);
        material.setDepthMask(false);
        material.getBlendMode().setDstColorFactor(GlStateManager.DestFactor.ONE);
        renderer.setBloomEffect(true);
        renderer.getCull().setEnable(false); // Long beams must not be culled by their origin alone.
    }

    private static Curve taper(float initial) {
        Curve curve=new Curve(0,initial,0,initial,initial,"lifetime","size");
        curve.setCurves(new ECBCurves(0,1,.25f,1,.75f,.2f,1,0));
        return curve;
    }

    private static ParticleEmitter particles(Vec3 at,int lifetime) {
        ParticleEmitter emitter=new ParticleEmitter();
        var c=emitter.config;
        c.setDuration(1);c.setLooping(false);c.setMaxParticles(800);
        c.setParallelUpdate(false);c.setParallelRendering(false);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        c.setStartLifetime(NumberFunction.constant(lifetime));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartSize(new NumberFunction3(1,1,1));
        c.physics.setEnable(false);
        c.sizeOverLifetime.setEnable(true);
        c.sizeOverLifetime.setSize(new NumberFunction3(taper(1),taper(1),taper(1)));
        material(c.material,c.renderer,FLARE);
        register(emitter,at);
        return emitter;
    }

    private static void mote(ParticleEmitter emitter,Vec3 offset,Vec3 velocity,float size,int color) {
        TileParticle p=new TileParticle(emitter,emitter.config,emitter.getRandomSource()) {
            { this.initialSize.set(size); }
            @Override public void tick() {
                super.tick();
                float fade=Math.max(0,1-getT(0));
                setColor(new Vector4f(((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,fade*fade));
            }
        };
        p.setLocalPos(new Vector3f((float)offset.x,(float)offset.y,(float)offset.z),true);
        p.setInternalVelocity(new Vector3f((float)velocity.x,(float)velocity.y,(float)velocity.z));
        p.setSize(new Vector3f(size));p.setARGBColor(color);
        emitter.emitParticle(p);
    }

    public static Vec3 muzzle(LivingEntity caster) {
        return ScepterPose.stoneMuzzle(caster);
    }

    /** Where the stone is for this viewer: in the hand for our own first person, else on the staff. */
    public static Vec3 stone(Entity caster) {
        Minecraft mc=Minecraft.getInstance();
        if(caster==mc.player&&mc.options.getCameraType().isFirstPerson()) {
            Camera camera=mc.gameRenderer.getMainCamera();
            // Carry the hand's 70 degree projection into the world's.
            double k=Math.tan(Math.toRadians(mc.options.fov().get())/2)/Math.tan(Math.toRadians(35));
            Vector3f look=camera.getLookVector(),up=camera.getUpVector(),left=camera.getLeftVector();
            double x=STONE_X*k,y=STONE_Y*k,z=-STONE_Z;
            return camera.getPosition()
                .add(-left.x()*x+up.x()*y+look.x()*z,-left.y()*x+up.y()*y+look.y()*z,-left.z()*x+up.z()*y+look.z()*z);
        }
        return caster instanceof LivingEntity living?muzzle(living):caster.getEyePosition();
    }

    private static void beam(Vec3 from,Vec3 to,float width,int color,int duration) {
        BeamEmitter emitter=new BeamEmitter();
        var c=emitter.getConfig();
        c.setDuration(duration);c.setLooping(false);c.setWidth(taper(width));
        c.setColor(NumberFunction.color(color));
        Vec3 delta=to.subtract(from);c.getEnd().set((float)delta.x,(float)delta.y,(float)delta.z);
        c.setEmitRate(NumberFunction.constant(.13f));
        material(c.material,c.renderer,BEAM);
        register(emitter,from);
        emitter.init();
    }

    /** Converging light while the stone fills; called once a tick per charging caster. */
    public static void charging(Entity caster,float charge) {
        var level=Minecraft.getInstance().level;
        if(level==null)return;
        Vec3 at=stone(caster);
        ParticleEmitter emitter=particles(at,5);
        int count=1+Math.round(3*charge);
        for(int i=0;i<count;i++) {
            Vec3 out=new Vec3(level.random.nextGaussian(),level.random.nextGaussian(),level.random.nextGaussian()).normalize()
                .scale(.28+.35*level.random.nextDouble());
            mote(emitter,out,out.scale(-.19),.05f+.07f*charge,i%2==0?0xff9fe8ff:0xff2a8cff);
        }
        mote(emitter,Vec3.ZERO,Vec3.ZERO,.10f+.30f*charge,0xff1f8bff);
    }

    public static void blast(int entity,CompoundTag n) {
        var level=Minecraft.getInstance().level;
        if(level==null)return;
        Vec3 origin=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        Vec3 destination=new Vec3(n.getDouble("tx"),n.getDouble("ty"),n.getDouble("tz"));
        float power=n.getFloat("power");
        boolean charged=power>0,hit=n.getBoolean("hit"),floor=n.getBoolean("floor");
        var viewer=Minecraft.getInstance().player;
        Entity caster=level.getEntity(entity);
        if(caster!=null&&caster==viewer)origin=stone(caster);
        Vec3 ray=destination.subtract(origin);
        if(ray.lengthSqr()<.001)return;
        if(viewer!=null&&viewer.getId()==entity)com.hexgodofstories.client.leviathan.LeviathanEffects.scepterRecoil(charged?.8f+.9f*power:.45f);
        Vec3 direction=ray.normalize();

        int life=charged?8+Math.round(7*power):5;
        float w=charged?1+1.6f*power:.55f;
        beam(origin,destination,.42f*w,0xff126bff,life);
        beam(origin,destination,.18f*w,0xff58d9ff,life);
        beam(origin,destination,.065f*w,0xfff4ffff,life);
        if(charged)beam(origin,destination,.9f*w,0x66103cff,life+4);

        // The wake along the whole beam, bounded however long it is.
        ParticleEmitter wake=particles(origin,charged?12:7);
        int count=Math.min(charged?420:180,Math.max(8,(int)(ray.length()*(charged?4:2))));
        for(int i=0;i<count;i++) {
            double t=i/(double)count;
            double spread=charged?.07+.12*power:.05;
            Vec3 jitter=new Vec3(level.random.nextGaussian()*spread,level.random.nextGaussian()*spread,level.random.nextGaussian()*spread);
            mote(wake,ray.scale(t).add(jitter),direction.scale(.06),i%4==0?.19f*w:.33f*w,i%4==0?0xffe5ffff:0xff209dff);
        }
        // Muzzle flash off the stone.
        mote(wake,Vec3.ZERO,Vec3.ZERO,.85f*w,0xff147bff);
        mote(wake,Vec3.ZERO,Vec3.ZERO,.36f*w,0xfff4ffff);

        // Each body the beam went through: a burst of light and what the hole threw out.
        Tag through=n.get("through");
        if(through instanceof ListTag list) {
            for(int i=0;i+2<list.size();i+=3) {
                Vec3 at=new Vec3(list.getDouble(i),list.getDouble(i+1),list.getDouble(i+2));
                ParticleEmitter burst=particles(at,14);
                mote(burst,Vec3.ZERO,Vec3.ZERO,1.1f*w,0xff2a9dff);
                mote(burst,Vec3.ZERO,Vec3.ZERO,.45f*w,0xfff4ffff);
                for(int k=0;k<24;k++) {
                    Vec3 spray=direction.scale(.18+level.random.nextDouble()*.3)
                        .add(level.random.nextGaussian()*.08,level.random.nextGaussian()*.08,level.random.nextGaussian()*.08);
                    mote(burst,Vec3.ZERO,spray,.14f,k%3==0?0xffeaffff:0xff3da8ff);
                }
                for(int k=0;k<8+Math.round(10*power);k++)
                    Vfx.spark(HexGodOfStories.BLOOD.get(),at,direction.scale(.12+level.random.nextDouble()*.14)
                        .add(level.random.nextGaussian()*.05,level.random.nextGaussian()*.05,level.random.nextGaussian()*.05));
            }
        }
        if(!hit)return;
        ParticleEmitter impact=particles(destination.add(0,floor?.04:0,0),charged?34:18);
        float scale=charged?1+1.4f*power:.45f;
        mote(impact,Vec3.ZERO,Vec3.ZERO,(floor?7.5f:6.5f)*scale,0xff159bff);
        mote(impact,Vec3.ZERO,Vec3.ZERO,2.2f*scale,0xffefffff);
        // Shock ring, then a dense blue and white burst.
        int ring=charged?128:48;
        for(int i=0;i<ring;i++) {
            double angle=i*Math.PI*2/ring;
            Vec3 radial=new Vec3(Math.cos(angle),0,Math.sin(angle));
            mote(impact,radial.scale(.20),radial.scale(.40*scale),.44f,0xff72dcff);
        }
        if(charged) {
            // A second, slower ring for the heavy discharge.
            for(int i=0;i<64;i++) {
                double angle=i*Math.PI*2/64;
                Vec3 radial=new Vec3(Math.cos(angle),.05,Math.sin(angle));
                mote(impact,radial.scale(.3),radial.scale(.22*scale),.8f,0xff1f7dff);
            }
        }
        int sparks=charged?Math.round(260*scale):90;
        for(int i=0;i<sparks;i++) {
            double angle=level.random.nextDouble()*Math.PI*2;
            double speed=(floor?.24:.22)*(charged?1+.4*power:.7)+level.random.nextDouble()*.24;
            Vec3 velocity=new Vec3(Math.cos(angle)*speed,.02+level.random.nextDouble()*.15*(charged?1.6:1),Math.sin(angle)*speed);
            mote(impact,Vec3.ZERO,velocity,i%3==0?.20f:.36f,i%3==0?0xffeaffff:0xff259bff);
        }
    }

    /** The shot as the caster hears it, the tick they let go; everyone else hears it from the server. */
    public static void fired(Player caster,float power) {
        if(power>0) {
            ScepterClient.play(HexGodOfStories.SCEPTER_BEAM.get(),caster.getX(),caster.getEyeY(),caster.getZ(),1.6f+.8f*power,1.08f-.22f*power);
            ScepterClient.play(HexGodOfStories.SCEPTER_SHOT.get(),caster.getX(),caster.getEyeY(),caster.getZ(),1.2f,.62f);
        } else ScepterClient.play(HexGodOfStories.SCEPTER_SHOT.get(),caster.getX(),caster.getEyeY(),caster.getZ(),1.1f,
            .94f+caster.getRandom().nextFloat()*.14f);
    }
}
