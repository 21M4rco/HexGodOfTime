package com.hexgodofstories.client.particle;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The mod's own particles. They draw additively against a soft sprite rather than through vanilla's
 * translucent sheet, which is what makes sorcery read as light rather than as a cloud of squares,
 * and each behaviour carries its own drift, spin and fade so effects can be sparse and still look
 * designed instead of relying on sheer count.
 *
 * <p>Nothing here arrives at full strength. Every particle opens over its first few ticks — alpha
 * and size together — so an effect gathers into existence instead of being switched on. The nebula
 * family in particular takes its time: a cloud puff spends a fifth of its life growing before it
 * begins to thin, which is what makes a summoning read as something forming rather than appearing.
 */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class HexParticles {
    /** Additive, depth-tested but not depth-writing, so overlapping motes accumulate into a glow. */
    public static final ParticleRenderType GLOW=new ParticleRenderType() {
        @Override public void begin(BufferBuilder builder,TextureManager textures) {
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getParticleShader);
            RenderSystem.setShaderTexture(0,TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,GlStateManager.DestFactor.ONE);
            builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.PARTICLE);
        }
        @Override public void end(Tesselator tesselator) {
            tesselator.end();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
        }
        @Override public String toString() {return "hexgodofstories:glow";}
    };

    @SubscribeEvent public static void providers(RegisterParticleProvidersEvent e) {
        e.registerSpriteSet(HexGodOfStories.EMBER.get(),set->new Drifting.Provider(set,.24f,.86f,.51f,.018,false));
        e.registerSpriteSet(HexGodOfStories.GOLD_EMBER.get(),set->new Drifting.Provider(set,.92f,.73f,.38f,.012,false));
        e.registerSpriteSet(HexGodOfStories.MOTE.get(),set->new Drifting.Provider(set,.83f,.94f,.86f,.0015,true));
        e.registerSpriteSet(HexGodOfStories.BLOOD.get(),Drip.Provider::new);
        e.registerSpriteSet(HexGodOfStories.RUNE.get(),Glyph.Provider::new);
        e.registerSpriteSet(HexGodOfStories.SHARD.get(),Sliver.Provider::new);
        e.registerSpriteSet(HexGodOfStories.NEBULA.get(),set->new Cloud.Provider(set,.22f,.93f,.60f,.62f,true,70));
        e.registerSpriteSet(HexGodOfStories.VEIL.get(),set->new Cloud.Provider(set,.52f,.92f,.74f,1.15f,true,90));
        e.registerSpriteSet(HexGodOfStories.SMOKE.get(),set->new Cloud.Provider(set,.16f,.29f,.24f,.85f,false,110));
        e.registerSpriteSet(HexGodOfStories.STAR.get(),Flare.Provider::new);
        e.registerSpriteSet(HexGodOfStories.TEMPORAL_DUST.get(),Dust.Provider::new);
        e.registerSpriteSet(HexGodOfStories.BRANCH_THREAD.get(),Thread_.Provider::new);
        e.registerSpriteSet(HexGodOfStories.SPECTRAL.get(),Spectral.Provider::new);
        e.registerSpriteSet(HexGodOfStories.METEOR_FIRE.get(),Flame.Provider::new);
        e.registerSpriteSet(HexGodOfStories.CINDER.get(),Cinder.Provider::new);
        e.registerSpriteSet(HexGodOfStories.ASH.get(),set->new Cloud.Provider(set,.21f,.19f,.18f,1.35f,false,150));
    }

    /**
     * The residue of erasure: a fragment small enough to be dust, bright enough to see, and carried along
     * whatever swept it off the thing it used to be part of. It keeps the velocity it was given rather
     * than settling, because it is not falling — it is being taken downstream.
     */
    public static final class Dust extends TextureSheetParticle {
        private final float tone;
        Dust(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            tone=random.nextFloat();
            hasPhysics=false;friction=.975f;
            lifetime=26+random.nextInt(30);
            quadSize=.035f+random.nextFloat()*.045f;
            alpha=0;
            pickSprite(sprites);
            shade(0);
        }
        private void shade(float t) {
            int colour=com.hexgodofstories.client.TemporalPalette.shade(tone+t*.45f);
            rCol=(colour>>16&255)/255f;gCol=(colour>>8&255)/255f;bCol=(colour&255)/255f;
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            float t=age/(float)lifetime;
            shade(t);
            yd+=.0012;
            alpha=bloom(age,0,3)*(1-t)*(1-t*.4f);
            quadSize*=.987f;
        }
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Dust(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }

    /** A thin strand of loose timeline, stretched along its own travel and fading from both ends. */
    public static final class Thread_ extends TextureSheetParticle {
        private final float tone;
        Thread_(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            tone=random.nextFloat();
            hasPhysics=false;friction=.985f;
            lifetime=20+random.nextInt(18);
            quadSize=.10f+random.nextFloat()*.10f;
            roll=random.nextFloat()*6.28f;oRoll=roll;
            alpha=0;
            pickSprite(sprites);
            int colour=com.hexgodofstories.client.TemporalPalette.shade(tone);
            rCol=(colour>>16&255)/255f;gCol=(colour>>8&255)/255f;bCol=(colour&255)/255f;
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            oRoll=roll;roll+=.02f;
            float t=age/(float)lifetime;
            alpha=bloom(age,0,2)*Math.min(1,(1-t)*2.1f);
            quadSize*=.994f;
        }
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Thread_(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }

    /** A mote that never settles on one colour: several currents passing through the same speck. */
    public static final class Spectral extends TextureSheetParticle {
        private final float tone,rate;
        Spectral(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            tone=random.nextFloat();
            rate=.010f+random.nextFloat()*.026f;
            hasPhysics=false;friction=.97f;
            lifetime=34+random.nextInt(34);
            quadSize=.05f+random.nextFloat()*.06f;
            alpha=0;
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            int colour=com.hexgodofstories.client.TemporalPalette.shade(tone+age*rate);
            rCol=(colour>>16&255)/255f;gCol=(colour>>8&255)/255f;bCol=(colour&255)/255f;
            float t=age/(float)lifetime;
            alpha=bloom(age,0,5)*(1-t)*(1-t);
        }
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Spectral(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }

    /**
     * Flame torn off a stone falling far too fast. White at the leading edge, cooling through yellow and
     * orange as it is left behind, and swelling as it goes because it is being shed, not burning in place.
     */
    public static final class Flame extends TextureSheetParticle {
        Flame(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            hasPhysics=false;friction=.90f;
            lifetime=12+random.nextInt(14);
            quadSize=.22f+random.nextFloat()*.30f;
            roll=random.nextFloat()*6.28f;oRoll=roll;
            alpha=0;
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            oRoll=roll;roll+=.05f;
            float t=age/(float)lifetime;
            rCol=1;
            gCol=Math.max(.18f,.98f-t*1.15f);
            bCol=Math.max(.04f,.72f-t*1.9f);
            quadSize*=1.035f;
            alpha=bloom(age,0,2)*(1-t)*(1-t*.45f)*.95f;
        }
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Flame(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }

    /** Ablated rock: a hot speck thrown clear of the meteor that cools and drops away behind it. */
    public static final class Cinder extends TextureSheetParticle {
        Cinder(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            hasPhysics=true;friction=.965f;gravity=.28f;
            lifetime=28+random.nextInt(34);
            quadSize=.05f+random.nextFloat()*.06f;
            alpha=0;
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            float t=age/(float)lifetime;
            rCol=1;gCol=Math.max(.10f,.80f-t*1.0f);bCol=Math.max(.03f,.32f-t*.9f);
            alpha=bloom(age,0,2)*(1-t)*(1-t*.3f);
        }
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Cinder(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }

    /**
     * The shared opening curve. Everything fades and grows through the same smoothstep so separate
     * effects layered on one another still look like one material catching light.
     */
    static float bloom(int age,float partial,float ticks) {
        float t=Math.max(0,Math.min(1,(age+partial)/Math.max(1,ticks)));
        return t*t*(3-2*t);
    }

    /** Embers and suspended dust: the same motion with different weight. */
    public static final class Drifting extends TextureSheetParticle {
        private final double lift;
        private final boolean suspended;
        Drifting(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites,
                 float r,float g,float b,double lift,boolean suspended) {
            super(level,x,y,z);
            this.lift=lift;this.suspended=suspended;
            xd=vx;yd=vy;zd=vz;
            rCol=r;gCol=g;bCol=b;
            hasPhysics=false;
            friction=suspended?.995f:.915f;
            lifetime=suspended?70+random.nextInt(50):22+random.nextInt(20);
            quadSize=(suspended?.022f:.075f)*(.7f+random.nextFloat()*.6f);
            alpha=0;
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            yd+=lift;
            float t=age/(float)lifetime;
            // Suspended dust gathers slowly; an ember catches quickly but still never starts lit.
            float growth=bloom(age,0,suspended?9:3);
            alpha=growth*(suspended?Math.min(1,(1-t)*2.4f):(1-t)*(1-t));
            if(!suspended)quadSize*=.982f;
        }
        record Provider(SpriteSet sprites,float r,float g,float b,double lift,boolean suspended) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Drifting(level,x,y,z,vx,vy,vz,sprites,r,g,b,lift,suspended);
            }
        }
    }

    /** A wound's spatter: small, dark and subject to gravity, so bleeding reads as physical. */
    public static final class Drip extends TextureSheetParticle {
        Drip(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            rCol=.62f;gCol=.09f;bCol=.11f;
            hasPhysics=true;friction=.98f;gravity=.75f;
            lifetime=26+random.nextInt(18);
            quadSize=.045f+random.nextFloat()*.03f;
            alpha=0;
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;}
        @Override public void tick() {super.tick();alpha=bloom(age,0,2)*(1-age/(float)lifetime*.8f);}
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Drip(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }

    /** A seidr glyph that turns as it blooms open and thins away. */
    public static final class Glyph extends TextureSheetParticle {
        private final float spin,peak;
        Glyph(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            rCol=.44f;gCol=.91f;bCol=.60f;
            hasPhysics=false;friction=.9f;
            lifetime=26+random.nextInt(10);
            spin=(random.nextBoolean()?1:-1)*(.035f+random.nextFloat()*.03f);
            peak=.42f+random.nextFloat()*.22f;
            quadSize=.05f;
            roll=random.nextFloat()*6.28f;
            oRoll=roll;
            alpha=0;
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            oRoll=roll;roll+=spin;
            float t=age/(float)lifetime;
            quadSize=peak*(float)Math.sin(Math.min(1,t*1.15)*Math.PI*.85);
            alpha=bloom(age,0,4)*(1-t)*(1-t*.3f);
        }
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Glyph(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }

    /** Mirror glass: a spinning sliver that catches light on its way down. */
    public static final class Sliver extends TextureSheetParticle {
        private final float spin;
        Sliver(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            rCol=.80f;gCol=.95f;bCol=.88f;
            hasPhysics=true;friction=.96f;gravity=.42f;
            lifetime=30+random.nextInt(26);
            quadSize=.07f+random.nextFloat()*.09f;
            spin=(random.nextBoolean()?1:-1)*(.12f+random.nextFloat()*.16f);
            roll=random.nextFloat()*6.28f;
            oRoll=roll;
            alpha=0;
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            oRoll=roll;roll+=spin;
            float t=age/(float)lifetime;
            alpha=bloom(age,0,3)*Math.min(1,(1-t)*1.8f);
        }
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Sliver(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }

    /**
     * The nebula family: a slow, soft volume that grows open, turns, and drifts apart. This is the
     * look the flight cloud established, available to any ability that wants weight behind its
     * sparks rather than more of them.
     */
    public static final class Cloud extends TextureSheetParticle {
        private final float peak,spin;
        private final boolean additive;
        private final float open;
        Cloud(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites,
              float r,float g,float b,float size,boolean additive,int life) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            rCol=r;gCol=g;bCol=b;
            this.additive=additive;
            hasPhysics=false;
            friction=.965f;
            lifetime=(int)(life*(.72f+random.nextFloat()*.55f));
            peak=size*(.62f+random.nextFloat()*.7f);
            open=lifetime*.28f;
            spin=(random.nextBoolean()?1:-1)*(.006f+random.nextFloat()*.014f);
            roll=random.nextFloat()*6.28f;
            oRoll=roll;
            quadSize=.01f;
            alpha=0;
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return additive?GLOW:ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;}
        @Override public void tick() {
            super.tick();
            oRoll=roll;roll+=spin;
            // Rising a little as it expands keeps a cloud from reading as a flat decal.
            yd+=additive?.0016:.0009;
            float t=age/(float)lifetime;
            float growth=bloom(age,0,open);
            quadSize=peak*(.35f+.65f*growth)*(1+t*.55f);
            alpha=growth*(1-t)*(1-t)*(additive?.62f:.5f);
        }
        record Provider(SpriteSet sprites,float r,float g,float b,float size,boolean additive,int life) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Cloud(level,x,y,z,vx,vy,vz,sprites,r,g,b,size,additive,life);
            }
        }
    }

    /** A struck flare: opens fast, holds for an instant, then collapses. Used where a spell lands. */
    public static final class Flare extends TextureSheetParticle {
        private final float peak,spin;
        Flare(ClientLevel level,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=vx;yd=vy;zd=vz;
            rCol=.72f;gCol=.98f;bCol=.82f;
            hasPhysics=false;friction=.88f;
            lifetime=14+random.nextInt(12);
            peak=.26f+random.nextFloat()*.22f;
            spin=(random.nextBoolean()?1:-1)*.03f;
            roll=random.nextFloat()*6.28f;
            oRoll=roll;
            quadSize=.01f;
            alpha=0;
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            oRoll=roll;roll+=spin;
            float t=age/(float)lifetime;
            float growth=bloom(age,0,4);
            quadSize=peak*growth*(float)Math.max(.15,Math.cos(t*Math.PI*.5));
            alpha=growth*(1-t)*(1-t*.4f);
        }
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Flare(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }
}
