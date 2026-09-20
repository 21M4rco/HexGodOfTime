package com.loki.client;

import com.loki.Loki;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * A body being taken out of the timeline.
 *
 * <p>The whole point is that the destruction travels <em>through</em> the victim. Nothing here fades a
 * model out uniformly, hides a renderer, or shrinks a creature into nothing. An erasure front crosses the
 * body along the torrent's direction: everything behind it is already gone, everything ahead of it is
 * still standing, and the narrow band on the front is actively coming apart into glowing fragments that
 * are swept downstream. The victim therefore spends most of the sequence as a visibly incomplete
 * silhouette, which is exactly what the reference shows.
 *
 * <p>True geometry clipping is not available across arbitrary modded renderers, so the body is rebuilt as
 * a grid of cuboid fragments sized to the entity's own bounding box and textured from whatever sheet its
 * renderer reports. That approximation works for every living entity in the game, vanilla or modded,
 * because it never touches the foreign renderer — it replaces it. Every lookup is guarded, and a renderer
 * that refuses to answer falls back to untextured temporal fragments rather than failing.
 *
 * <p>The material does not tumble away as debris. It is <em>drawn out</em>: a fragment the front has
 * reached stretches downstream into fine parallel filaments along the torrent, thinning as it goes, so the
 * body smears rather than crumbles and the silhouette stays readable inside the streaking right up until
 * there is none of it left. That directional smear is the whole character of the reference, and cuboids
 * spinning off in every direction read as rubble instead.
 *
 * <p>This is also where the death animation is refused. Once the sequence is finished nothing is drawn at
 * all, and the ordinary renderer stays stood down for as long as the body is lying there dead — which for a
 * player is until they respawn, since a player's corpse cannot simply be removed from the world. A corpse
 * tipping over after the body has already come apart would undo the entire effect, so it is never shown.
 */
public final class ErasureRenderer {
    private ErasureRenderer() {}

    private record Fading(Vec3 direction,long start,int duration,float power) {}
    private static final Map<Integer,Fading> FADING=new HashMap<>();
    /** Below this the real model is still drawn; past it the fragment body replaces it. */
    private static final float TAKEOVER=.18f;
    /** Ticks past the sequence before a body that is neither gone nor alive is given up on. */
    private static final int GRACE=10,ABANDON=2400;
    /** Ticks between bursts of dust. The sequence is long, so the rate is sparse rather than constant. */
    private static final int CADENCE=3;
    /** Half-width of the band that is actively breaking, as a fraction of the body's depth. */
    private static final double BAND=.17;
    private static final int MAX_CELLS=900;
    private static final double VISIBLE=72*72;

    public static void clear() {FADING.clear();}

    public static void begin(int entity,CompoundTag n) {
        Vec3 direction=new Vec3(n.getDouble("dx"),n.getDouble("dy"),n.getDouble("dz"));
        if(direction.lengthSqr()<1e-8)direction=new Vec3(1,0,0);
        if(FADING.size()>48)FADING.clear();
        FADING.put(entity,new Fading(direction.normalize(),n.getLong("start"),Math.max(1,n.getInt("duration")),n.getFloat("power")));
        var mc=Minecraft.getInstance();
        if(mc.level!=null&&mc.level.getEntity(entity)!=null)
            BranchAudio.erase(mc.level.getEntity(entity).position());
    }

    /** 0 to 1 across the sequence, or -1 when this entity is not being erased. */
    public static float progress(int entity,float partial) {
        Fading f=FADING.get(entity);
        if(f==null)return -1;
        return Mth.clamp((ClientState.now()+partial-f.start())/(float)f.duration(),0,1);
    }
    /** True once the fragment body has taken over, so the ordinary renderer must stand down. */
    public static boolean consumed(Entity e) {
        float p=progress(e.getId(),Minecraft.getInstance().getFrameTime());
        return p>=TAKEOVER;
    }
    /** True while a body is being erased at all: used to keep a caught player's own controls off. */
    public static boolean erasing(Entity e) {return e!=null&&FADING.containsKey(e.getId());}

    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level==null){clear();return;}
        long now=ClientState.now();
        FADING.entrySet().removeIf(e->{
            Entity victim=mc.level.getEntity(e.getKey());
            if(victim==null)return true;
            long over=now-e.getValue().start()-e.getValue().duration();
            if(over<GRACE)return false;
            // Past the sequence. A body that has gone, or that genuinely survived, can be let go of. One
            // still lying there dead stays hidden, because that corpse is the death animation.
            if(over>ABANDON)return true;
            return victim.isAlive();
        });
        if(FADING.isEmpty()||mc.player==null)return;
        if(now%CADENCE!=0)return;
        Vec3 eye=mc.player.getEyePosition();
        for(var entry:FADING.entrySet()) {
            Entity e=mc.level.getEntity(entry.getKey());
            if(e==null||e.position().distanceToSqr(eye)>VISIBLE)continue;
            Fading f=entry.getValue();
            float phase=Mth.clamp((now-f.start())/(float)f.duration(),0,1);
            if(phase>=1)continue;
            Vec3 front=e.position().add(0,e.getBbHeight()*(1-phase*.7),0);
            // Dust, wisps and threads, all swept the way the torrent was going.
            Vfx.cone(Loki.TEMPORAL_DUST.get(),front,f.direction(),Vfx.count(3+f.power()*3),.34,.16);
            Vfx.cone(Loki.BRANCH_THREAD.get(),front,f.direction(),Vfx.count(1+f.power()*1.5f),.22,.14);
            Vfx.cloud(Loki.NEBULA.get(),front,e.getBbWidth()+.3,Vfx.count(1.1f),.02);
            if(now%2==0)Vfx.spark(Loki.SPECTRAL.get(),front,f.direction().scale(.08));
            if(phase>.8f)Vfx.spark(Loki.STAR.get(),front,f.direction().scale(.05));
        }
    }

    // ---------------------------------------------------------------------- render ---

    private static final BranchVfx.Painter PAINTER=new BranchVfx.Painter();

    public static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,float partial) {
        if(FADING.isEmpty())return;
        var mc=Minecraft.getInstance();
        if(mc.level==null){PAINTER.discard();return;}
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        double time=ClientState.now()+partial;
        int budget=MAX_CELLS;
        for(var entry:new ArrayList<>(FADING.entrySet())) {
            Entity e=mc.level.getEntity(entry.getKey());
            if(e==null)continue;
            double distance=e.position().distanceToSqr(camera);
            if(distance>VISIBLE)continue;
            float phase=progress(entry.getKey(),partial);
            // Finished. Nothing is drawn, and nothing vanilla is drawn either, so the body is simply gone.
            if(phase<0||phase>=1)continue;
            try {
                budget-=body(PAINTER,e,entry.getValue(),phase,partial,distance,time,budget);
                crawl(PAINTER,e,entry.getValue(),phase,partial,time);
                flare(PAINTER,e,entry.getValue(),phase,partial,time);
            } catch(Exception ignored) {
                // An unfamiliar renderer or model costs this one body its fragments, never the frame.
            }
            if(budget<=0)break;
        }
        PAINTER.flush(pose,buffers);
    }

    /**
     * The body as fragments. Ahead of the front they sit exactly where the model was and keep its
     * colours; on the front they are lifted, spun and lit; behind it they are simply not drawn.
     *
     * @return how many cells this body drew, so a crowd cannot blow the frame budget.
     */
    private static int body(BranchVfx.Painter painter,Entity e,Fading f,
                            float phase,float partial,double distance,double time,int budget) {
        if(phase<TAKEOVER)return 0;
        boolean far=distance>32*32;
        double width=Math.max(.2,e.getBbWidth()),height=Math.max(.3,e.getBbHeight());
        int nx=Mth.clamp((int)Math.round(width*(far?3:5)),2,5);
        int ny=Mth.clamp((int)Math.round(height*(far?3:5)),3,10);
        int nz=nx;
        if(nx*ny*nz>budget)return 0;

        ResourceLocation texture=texture(e);
        RenderType skin=RenderType.entityTranslucent(texture==null?WorldEffects.WHITE:texture);
        RenderType glow=BranchVfx.glow();

        float yaw=e instanceof LivingEntity living?Mth.rotLerp(partial,living.yBodyRotO,living.yBodyRot)
                                                 :Mth.rotLerp(partial,e.yRotO,e.getYRot());
        double rad=-Math.toRadians(yaw),cos=Math.cos(rad),sin=Math.sin(rad);
        Vec3 base=e.getPosition(partial);
        Vec3 centre=base.add(0,height*.5,0);
        Vec3 d=f.direction();
        // Half the body's reach along the torrent, so the front's travel is normalised to the body.
        double extent=(width*Math.abs(d.x)+height*Math.abs(d.y)+width*Math.abs(d.z))*.5;
        if(extent<1e-4)extent=.5;
        double entry=centre.dot(d)-extent;
        // A little overshoot at both ends: the front arrives before the leading edge and leaves after
        // the trailing one, so no fragment is stranded and none disappears without breaking first.
        double frontAt=phase*1.18-.09;
        // Two directions square to the flow, so the filaments can be spread across it rather than stacked.
        Vec3 across=BranchVfx.perpendicular(d),over=across.cross(d).normalize();
        int drawn=0;

        for(int i=0;i<nx;i++)for(int j=0;j<ny;j++)for(int k=0;k<nz;k++) {
            double lx=-width*.5+(i+.5)/nx*width;
            double ly=(j+.5)/ny*height;
            double lz=-width*.5+(k+.5)/nz*width;
            Vec3 at=base.add(lx*cos-lz*sin,ly,lx*sin+lz*cos);
            double s=(at.dot(d)-entry)/(2*extent);
            if(s<frontAt-BAND)continue;
            int cell=(i*13+j*7+k*3);
            double half=Math.min(width/nx,height/ny)*.52;
            if(s>frontAt+BAND) {
                // Not reached yet. Still standing, still wearing its own colours.
                cube(painter,skin,at,half,rad,uv(texture,i,j,nx,ny),0xffffff,.97f);
                drawn++;
                continue;
            }
            // On the front: caught, drawn out, and carried off down the torrent.
            double bite=Mth.clamp((frontAt+BAND-s)/(BAND*2),0,1);
            // Only a little tumble. Too much and it reads as rubble rather than as something being pulled
            // apart along one direction.
            double spin=rad+bite*(TemporalLightning.rand(cell,1)-.5)*1.1;
            Vec3 thrown=at
                .add(d.scale(bite*(.35+f.power()*.5)))
                .add((TemporalLightning.rand(cell,2)-.5)*bite*.22,
                     (TemporalLightning.rand(cell,3)-.2)*bite*.26,
                     (TemporalLightning.rand(cell,4)-.5)*bite*.22);
            float left=(float)(1-bite);
            float[] patch=uv(texture,i,j,nx,ny);
            // What is left of the solid piece: shrinking fast, because it is being drawn into the streaks.
            cube(painter,skin,thrown,half*(.78-bite*.62),spin,patch,0xffffff,left*.85f);
            // The smear. Several fine filaments off the same piece, offset across the flow so a body reads
            // as hundreds of parallel lines rather than as a handful of comet tails.
            double smear=bite*(2.4+f.power()*4.2);
            if(smear>.05) {
                for(int strand=0;strand<3;strand++) {
                    double spread=(TemporalLightning.rand(cell*3+strand,5)-.5)*half*1.7;
                    double lift=(TemporalLightning.rand(cell*3+strand,6)-.5)*half*1.7;
                    Vec3 from=thrown.add(across.scale(spread)).add(over.scale(lift));
                    Vec3 to=from.add(d.scale(smear*(.55+TemporalLightning.rand(cell*3+strand,7)*.85)));
                    // The near half keeps the victim's own colours; the far half has already become light.
                    streak(painter,skin,from,to,half*(.30-bite*.17),patch,0xffffff,left*.62f);
                    streak(painter,glow,from.add(d.scale(smear*.35)),to,half*(.17-bite*.09),null,
                        TemporalPalette.hot((float)(time*.06+TemporalPalette.offset(cell+strand)),(float)bite*.65f),
                        (.22f+.30f*(float)bite)*left);
                }
            }
            drawn++;
        }
        return drawn;
    }

    /** A hot sheet of light exactly where the front is cutting, so the cut itself is visible. */
    private static void flare(BranchVfx.Painter painter,Entity e,Fading f,float phase,float partial,double time) {
        double width=Math.max(.2,e.getBbWidth()),height=Math.max(.3,e.getBbHeight());
        Vec3 centre=e.getPosition(partial).add(0,height*.5,0);
        Vec3 d=f.direction();
        double extent=(width*Math.abs(d.x)+height*Math.abs(d.y)+width*Math.abs(d.z))*.5;
        Vec3 at=centre.add(d.scale((phase*1.18-.09-.5)*2*Math.max(.1,extent)));
        double size=Math.max(width,height)*(.55+.25*Math.sin(time*.5))*(1-phase*.35);
        BranchVfx.billboard(painter,BranchVfx.glow(),at,size,time*.1,TemporalPalette.hot((float)(time*.08),.85f),
            (.42f-.2f*phase)*(phase<TAKEOVER?phase/TAKEOVER:1));
    }

    /** Arcs over whatever is left of the body. They go when it goes. */
    private static void crawl(BranchVfx.Painter painter,Entity e,Fading f,float phase,float partial,double time) {
        double width=Math.max(.2,e.getBbWidth()),height=Math.max(.3,e.getBbHeight());
        Vec3 base=e.getPosition(partial);
        int arcs=1+(int)(f.power()*2);
        for(int i=0;i<arcs;i++) {
            long seed=(long)(time/TemporalLightning.FLICKER)*89+e.getId()*7+i;
            // Both ends land on the part that has not been reached yet, so the arcs die with the body.
            double surviving=Math.max(.12,1-phase);
            Vec3 from=base.add((TemporalLightning.rand(seed,1)-.5)*width,height*(1-surviving*TemporalLightning.rand(seed,2)),(TemporalLightning.rand(seed,3)-.5)*width);
            Vec3 to=base.add((TemporalLightning.rand(seed,4)-.5)*width,height*(1-surviving*TemporalLightning.rand(seed,5)),(TemporalLightning.rand(seed,6)-.5)*width);
            TemporalLightning.draw(painter,BranchVfx.strand(),TemporalLightning.bolt(seed,from,to,4,width*.32,1),
                width*.045,(float)(time*.06+i*.3),.55f*(1-phase*.45f));
        }
    }

    /**
     * One filament of smeared material: a camera-facing band from where the piece was to where the torrent
     * has taken it, tapering as it goes. Handed a patch of the victim's sheet it carries their colours;
     * handed none it is pure light, which is what the far end of every streak becomes.
     */
    private static void streak(BranchVfx.Painter painter,RenderType type,Vec3 from,Vec3 to,double width,
                               float[] patch,int colour,float alpha) {
        if(width<=0||alpha<=.004f)return;
        Vec3 along=to.subtract(from);
        if(along.lengthSqr()<1e-8)return;
        Vec3 camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 side=along.cross(from.subtract(camera));
        if(side.lengthSqr()<1e-12)side=BranchVfx.perpendicular(along.normalize());
        side=side.normalize().scale(width);
        // Pinched to nothing at the trailing end: the filament thins out rather than stopping square.
        Vec3 tip=side.scale(.12);
        float[] uv=patch==null?null:new float[]{patch[0],patch[1],patch[2],patch[1],patch[2],patch[3],patch[0],patch[3]};
        painter.quad(type,from.subtract(side),from.add(side),to.add(tip),to.subtract(tip),uv,colour,alpha);
    }

    /** Six faces, rotated with the body. Cuboids rather than sprites, because this is Minecraft. */
    private static void cube(BranchVfx.Painter painter,RenderType type,Vec3 at,double half,double yaw,float[] uv,int colour,float alpha) {
        double cos=Math.cos(yaw)*half,sin=Math.sin(yaw)*half;
        Vec3 x=new Vec3(cos,0,sin),z=new Vec3(-sin,0,cos),y=new Vec3(0,half,0);
        Vec3[] c={
            at.subtract(x).subtract(y).subtract(z),at.add(x).subtract(y).subtract(z),
            at.add(x).subtract(y).add(z),      at.subtract(x).subtract(y).add(z),
            at.subtract(x).add(y).subtract(z), at.add(x).add(y).subtract(z),
            at.add(x).add(y).add(z),           at.subtract(x).add(y).add(z)};
        float[] patch={uv[0],uv[1],uv[2],uv[1],uv[2],uv[3],uv[0],uv[3]};
        painter.quad(type,c[4],c[5],c[6],c[7],patch,colour,alpha);
        painter.quad(type,c[3],c[2],c[1],c[0],patch,colour,alpha);
        painter.quad(type,c[0],c[1],c[5],c[4],patch,colour,alpha);
        painter.quad(type,c[2],c[3],c[7],c[6],patch,colour,alpha);
        painter.quad(type,c[1],c[2],c[6],c[5],patch,colour,alpha);
        painter.quad(type,c[3],c[0],c[4],c[7],patch,colour,alpha);
    }

    /**
     * A small patch of the victim's own sheet per fragment, chosen by where in the body the fragment sat.
     * On a player skin that walks up the torso and head; on an unknown modded sheet it stays in the
     * middle, where a creature's body almost always is. Either way each fragment carries the colours of
     * the thing being erased rather than a generic tint.
     */
    private static float[] uv(ResourceLocation texture,int i,int j,int nx,int ny) {
        if(texture==null)return new float[]{0,0,1,1};
        float u=.26f+.24f*(i/(float)Math.max(1,nx-1));
        float v=.22f+.44f*(1-j/(float)Math.max(1,ny-1));
        return new float[]{u,v,u+.035f,v+.035f};
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static ResourceLocation texture(Entity e) {
        try {
            EntityRenderer renderer=Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e);
            return renderer==null?null:renderer.getTextureLocation(e);
        } catch(Exception ignored) {
            return null;
        }
    }
}
