package com.loki.client.particle;

import com.loki.Loki;
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
 * Loki's own particles. They draw additively against a soft sprite rather than through vanilla's
 * translucent sheet, which is what makes sorcery read as light rather than as a cloud of squares,
 * and each behaviour carries its own drift, spin and fade so effects can be sparse and still look
 * designed instead of relying on sheer count.
 */
@Mod.EventBusSubscriber(modid=Loki.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class LokiParticles {
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
        @Override public String toString() {return "loki:glow";}
    };

    @SubscribeEvent public static void providers(RegisterParticleProvidersEvent e) {
        e.registerSpriteSet(Loki.EMBER.get(),set->new Drifting.Provider(set,.24f,.86f,.51f,.018,false));
        e.registerSpriteSet(Loki.GOLD_EMBER.get(),set->new Drifting.Provider(set,.92f,.73f,.38f,.012,false));
        e.registerSpriteSet(Loki.MOTE.get(),set->new Drifting.Provider(set,.83f,.94f,.86f,.0015,true));
        e.registerSpriteSet(Loki.BLOOD.get(),Drip.Provider::new);
        e.registerSpriteSet(Loki.RUNE.get(),Glyph.Provider::new);
        e.registerSpriteSet(Loki.SHARD.get(),Sliver.Provider::new);
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
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            yd+=lift;
            float t=age/(float)lifetime;
            alpha=suspended?Math.min(1,(1-t)*2.4f):(1-t)*(1-t);
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
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;}
        @Override public void tick() {super.tick();alpha=1-age/(float)lifetime*.8f;}
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
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            oRoll=roll;roll+=spin;
            float t=age/(float)lifetime;
            quadSize=peak*(float)Math.sin(Math.min(1,t*1.15)*Math.PI*.85);
            alpha=(1-t)*(1-t*.3f);
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
            pickSprite(sprites);
        }
        @Override public ParticleRenderType getRenderType() {return GLOW;}
        @Override public void tick() {
            super.tick();
            oRoll=roll;roll+=spin;
            float t=age/(float)lifetime;
            alpha=Math.min(1,(1-t)*1.8f);
        }
        record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,double vx,double vy,double vz) {
                return new Sliver(level,x,y,z,vx,vy,vz,sprites);
            }
        }
    }
}
