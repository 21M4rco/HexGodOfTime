package com.hexgodofstories.client;

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
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.util.ArrayList;
import java.util.List;

/** Real Photon emitters, rendered and ticked by Photon's particle engine. No vanilla substitute. */
public final class ScepterFx {
    private ScepterFx() { }
    private static final List<Emitter> ACTIVE=new ArrayList<>();
    private static final ResourceLocation FLARE=new ResourceLocation("hexgodofstories","textures/particle/scepter_flare.png");
    private static final ResourceLocation BEAM=new ResourceLocation("hexgodofstories","textures/particle/scepter_beam.png");

    public static void clear(){for(Emitter e:ACTIVE)e.remove(true);ACTIVE.clear();}

    private static void register(Emitter emitter,Vec3 at) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null)return;
        ACTIVE.removeIf(e->!e.isAlive());
        while(ACTIVE.size()>=64)ACTIVE.remove(0).remove(true);
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

    public static void charge(int entity) {
        var level=Minecraft.getInstance().level;
        if(level==null||!(level.getEntity(entity) instanceof LivingEntity caster))return;
        ParticleEmitter emitter=particles(muzzle(caster),6);
        // Effect callbacks follow the caster during the six-tick raise/snap animation.
        emitter.setEffect(new com.lowdragmc.photon.client.fx.IEffect() {
            @Override public net.minecraft.world.level.Level getLevel(){return level;}
            @Override public void updateFXObjectTick(com.lowdragmc.photon.client.gameobject.IFXObject object) {
                if(!caster.isAlive()){emitter.remove(true);return;}
                Vec3 at=muzzle(caster);emitter.setPos(at.x,at.y,at.z);
            }
        });
        mote(emitter,Vec3.ZERO,Vec3.ZERO,.34f,0xff1389ff);
        mote(emitter,Vec3.ZERO,Vec3.ZERO,.13f,0xffeeffff);
    }

    private static void beam(Vec3 from,Vec3 to,float width,int color) {
        BeamEmitter emitter=new BeamEmitter();
        var c=emitter.getConfig();
        c.setDuration(7);c.setLooping(false);c.setWidth(taper(width));
        c.setColor(NumberFunction.color(color));
        Vec3 delta=to.subtract(from);c.getEnd().set((float)delta.x,(float)delta.y,(float)delta.z);
        c.setEmitRate(NumberFunction.constant(.13f));
        material(c.material,c.renderer,BEAM);
        register(emitter,from);
        emitter.init();
    }

    public static void blast(int entity,Vec3 origin,Vec3 destination,boolean floor,boolean hit) {
        var level=Minecraft.getInstance().level;
        if(level==null)return;
        // The server snapshots the exact stone position used for this shot. Never pull the
        // visual origin back to the eyes/hand: the blue beam must visibly leave the crystal.
        Vec3 ray=destination.subtract(origin);
        if(ray.lengthSqr()<.001)return;
        beam(origin,destination,.42f,0xff126bff);
        beam(origin,destination,.18f,0xff58d9ff);
        beam(origin,destination,.065f,0xfff4ffff);
        ParticleEmitter wake=particles(origin,9);
        Vec3 direction=ray.normalize();
        // A bounded continuous blue/white Photon wake, including the full 100-block range.
        int count=Math.min(400,Math.max(8,(int)(ray.length()*4)));
        for(int i=0;i<count;i++) {
            double t=i/(double)count;
            Vec3 jitter=new Vec3(level.random.nextGaussian()*.07,level.random.nextGaussian()*.07,level.random.nextGaussian()*.07);
            mote(wake,ray.scale(t).add(jitter),direction.scale(.06),i%4==0?.19f:.33f,i%4==0?0xffe5ffff:0xff209dff);
        }
        mote(wake,Vec3.ZERO,Vec3.ZERO,.85f,0xff147bff);
        mote(wake,Vec3.ZERO,Vec3.ZERO,.36f,0xfff4ffff);
        if(!hit)return;
        ParticleEmitter impact=particles(destination.add(0,floor?.04:0,0),14);
        mote(impact,Vec3.ZERO,Vec3.ZERO,floor?2.3f:1.1f,0xff159bff);
        mote(impact,Vec3.ZERO,Vec3.ZERO,.6f,0xffefffff);
        int sparks=floor?90:38;
        for(int i=0;i<sparks;i++) {
            double angle=level.random.nextDouble()*Math.PI*2;
            double speed=floor?.10+level.random.nextDouble()*.09:.04+level.random.nextDouble()*.08;
            Vec3 velocity=new Vec3(Math.cos(angle)*speed,(floor?.025:.0)+level.random.nextDouble()*.10,Math.sin(angle)*speed);
            mote(impact,Vec3.ZERO,velocity,i%3==0?.16f:.3f,i%3==0?0xffeaffff:0xff259bff);
        }
    }
}
