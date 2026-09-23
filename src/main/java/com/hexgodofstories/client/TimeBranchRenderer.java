package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import java.util.*;

/**
 * Time Branch Unleashing, drawn.
 *
 * <p>The mass of this effect is geometry, not particles. A contained sphere is a deforming membrane of
 * additive quads with braided strands, filaments and custom arcs over it; the torrent is a tube of rings
 * whose radius, colour and brightness vary along its length, with several timeline branches helixing
 * around it, splitting away and rejoining. Both are batched through two render types. The particle
 * system is used for embellishment at a low, capped rate and nothing else, because building this out of
 * particles would cost thousands of entities a tick for a worse picture.
 *
 * <p>Everything is derived from two small packets and {@link BranchCharge}, so what is drawn here is the
 * same volume the server hit. Ring counts, strand counts, arc counts and dissolve counts all fall off
 * with distance, and the whole ability is skipped beyond a hundred blocks.
 */
public final class TimeBranchRenderer {
    private TimeBranchRenderer() {}

    /** An open torrent as the client knows it, including the soft cover it is going to take. */
    private static final class Torrent {
        final int caster;final Vec3 origin,direction;final double length;final float power;
        final int held;final long start;final int life;
        final List<BlockPos> volume=new ArrayList<>();final List<Double> reach=new ArrayList<>();
        final List<Boolean> dusty=new ArrayList<>();
        int dusted;
        Torrent(int caster,Vec3 origin,Vec3 direction,double length,float power,int held,long start,int life) {
            this.caster=caster;this.origin=origin;this.direction=direction;this.length=length;
            this.power=power;this.held=held;this.start=start;this.life=life;
        }
    }

    /** Caster entity id to the tick their charge began. */
    private static final Map<Integer,Long> CHARGING=new HashMap<>();
    private static final List<Torrent> TORRENTS=new ArrayList<>();
    private static final double VISIBLE=100*100;
    private static final int MAX_VOLUME=4200,MAX_DISSOLVE_DRAWN=200;

    public static void clear() {CHARGING.clear();TORRENTS.clear();}
    public static boolean charging(int id) {return CHARGING.containsKey(id);}
    /** Ticks the given caster has been holding, or -1 when they are not. Drives audio, HUD and pose. */
    public static int held(int id,float partial) {
        Long start=CHARGING.get(id);
        if(start==null)return -1;
        return (int)Mth.clamp(ClientState.now()+partial-start,0,BranchCharge.LIMIT);
    }

    public static void charge(int caster,CompoundTag n) {
        if(n.getBoolean("charging")) {
            if(CHARGING.size()>24)CHARGING.clear();
            CHARGING.put(caster,n.getLong("start"));
        } else CHARGING.remove(caster);
    }

    /**
     * A release. This is also what ends the charge on the client: one packet for the transition means the
     * sphere can never be left hanging by two packets arriving out of order.
     */
    public static void torrent(int caster,CompoundTag n) {
        CHARGING.remove(caster);
        var mc=Minecraft.getInstance();
        if(mc.level==null)return;
        Vec3 origin=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        Vec3 direction=new Vec3(n.getDouble("dx"),n.getDouble("dy"),n.getDouble("dz"));
        if(direction.lengthSqr()<1e-8)return;
        Torrent t=new Torrent(caster,origin,direction.normalize(),n.getDouble("length"),
            n.getFloat("power"),n.getInt("held"),n.getLong("start"),n.getInt("life"));
        gatherVolume(t);
        if(TORRENTS.size()>4)TORRENTS.remove(0);
        TORRENTS.add(t);
        BranchAudio.discharge(origin,t.power);
        TemporalScreen.trigger("branch_release",mc.player!=null&&mc.player.getId()==caster);
    }

    /**
     * The same walk the server made, over the same blocks, so each one is seen coming apart at the instant
     * it is taken out of the world. Done once per cast rather than streamed block by block.
     */
    private static void gatherVolume(Torrent t) {
        var level=Minecraft.getInstance().level;
        if(level==null)return;
        for(BlockPos pos:BeamPath.occupied(level,t.origin,t.direction,BranchCharge.SAFE,t.length,
                BranchCharge.eraseRadius(t.power),MAX_VOLUME)) {
            t.volume.add(pos);
            t.reach.add(BeamPath.along(t.origin,t.direction,Vec3.atCenterOf(pos)));
            // Turf comes apart into dust, a wall into fragments. One look-up per block, kept for the draw.
            t.dusty.add(SoftTerrain.soft(level,pos,level.getBlockState(pos)));
        }
    }

    // ------------------------------------------------------------------------ tick ---

    /** Particle embellishment and expiry, once a tick and capped. Never per frame, never per block. */
    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null){clear();return;}
        long now=ClientState.now();
        // A charge with no packet behind it is a caster who left; drop it rather than draw it forever.
        CHARGING.entrySet().removeIf(e->mc.level.getEntity(e.getKey())==null||now-e.getValue()>BranchCharge.LIMIT+40);
        TORRENTS.removeIf(t->now-t.start>t.life);
        Vec3 eye=mc.player.getEyePosition();

        for(var entry:CHARGING.entrySet()) {
            Entity caster=mc.level.getEntity(entry.getKey());
            if(caster==null||caster.position().distanceToSqr(eye)>VISIBLE)continue;
            int stage=BranchCharge.stage((int)(now-entry.getValue()));
            Vec3 focus=BranchCharge.focus(caster,1);
            double radius=BranchCharge.sphere((int)(now-entry.getValue()));
            // Loose material pulled in rather than thrown out: the sphere is taking, not giving.
            Vfx.gather(HexGodOfStories.NEBULA.get(),focus,radius*2.4,Vfx.count(stage*.45f),.09+stage*.02);
            if(stage>=2)Vfx.gather(HexGodOfStories.SMOKE.get(),focus,radius*3.2,Vfx.count(stage*.5f),.14);
            if(stage>=3&&now%2==0)Vfx.spark(HexGodOfStories.EMBER.get(),focus.add(
                (mc.level.random.nextDouble()-.5)*radius*2,(mc.level.random.nextDouble()-.5)*radius*2,
                (mc.level.random.nextDouble()-.5)*radius*2),Vec3.ZERO);
            if(stage>=4&&now%3==0)Vfx.spark(HexGodOfStories.STAR.get(),focus,Vec3.ZERO);
            // Dust lifted off the ground underneath, so the world reacts rather than just the caster.
            if(stage>=3&&now%4==0)Vfx.ring(HexGodOfStories.EMBER.get(),caster.position().add(0,.05,0),
                1.4+stage*.35,Vfx.count(stage*.8f),.01,.07);
        }

        for(Torrent t:TORRENTS) {
            double age=now-t.start;
            double front=BranchCharge.front(t.length,age);
            if(front<=0)continue;
            Vec3 head=t.origin.add(t.direction.scale(front));
            if(head.distanceToSqr(eye)<VISIBLE) {
                Vfx.cone(HexGodOfStories.EMBER.get(),head,t.direction,Vfx.count(2+t.power*3),.5,.25);
                Vfx.cloud(HexGodOfStories.NEBULA.get(),head,.8+t.power,Vfx.count(1+t.power*1.5f),.04);
                if(front>=t.length-.01f) {
                    // Losing coherence at the far end: filaments, then stars, then nothing.
                    Vfx.cone(HexGodOfStories.EMBER.get(),head,t.direction,Vfx.count(2+t.power*3),.36,.34);
                    if(now%2==0)Vfx.spark(HexGodOfStories.STAR.get(),head,t.direction.scale(.06));
                }
            }
            // Nebula rolling down the length of it, not only at the head: the torrent is a volume, and it
            // should read as one from the side as well as from the front.
            if(now%2==0)for(int i=0;i<3;i++) {
                double at=BranchCharge.SAFE+(front-BranchCharge.SAFE)*((i+((now/2)%4)*.25)/3.0%1);
                Vec3 point=t.origin.add(t.direction.scale(at));
                if(point.distanceToSqr(eye)>VISIBLE)continue;
                Vfx.cloud(HexGodOfStories.NEBULA.get(),point,1.1+t.power*1.5,Vfx.count(1+t.power),.018);
                if(i==0)Vfx.cloud(HexGodOfStories.VEIL.get(),point,1.6+t.power*2,1,.01);
            }
            // Each block gets one small handful as it goes, once, in the order the front reaches them.
            while(t.dusted<t.volume.size()) {
                if(BranchCharge.reaches(t.reach.get(t.dusted))>age)break;
                int index=t.dusted++;
                BlockPos pos=t.volume.get(index);
                Vec3 at=Vec3.atCenterOf(pos);
                if(at.distanceToSqr(eye)>3600)continue;
                boolean dust=index<t.dusty.size()&&t.dusty.get(index);
                Vfx.cone(dust?HexGodOfStories.EMBER.get():HexGodOfStories.SHARD.get(),at,t.direction,2,.14,.13);
                if((index&3)==0)Vfx.spark(HexGodOfStories.SMOKE.get(),at,t.direction.scale(.04));
            }
        }
    }

    // ---------------------------------------------------------------------- render ---

    private static final BranchVfx.Painter PAINTER=new BranchVfx.Painter();

    public static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,float partial) {
        if(CHARGING.isEmpty()&&TORRENTS.isEmpty())return;
        var mc=Minecraft.getInstance();
        if(mc.level==null){PAINTER.discard();return;}
        Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        double time=ClientState.now()+partial;
        for(var entry:CHARGING.entrySet()) {
            Entity caster=mc.level.getEntity(entry.getKey());
            if(caster==null||caster.isInvisible())continue;
            double distance=caster.position().distanceToSqr(camera);
            if(distance>VISIBLE)continue;
            int held=(int)Mth.clamp(time-entry.getValue(),0,BranchCharge.LIMIT);
            sphere(PAINTER,caster,held,BranchCharge.sphere(held),1,time,distance,partial,visibility(caster));
        }
        for(Torrent t:TORRENTS) {
            double age=time-t.start;
            if(age<0||age>t.life)continue;
            if(t.origin.distanceToSqr(camera)>VISIBLE*4)continue;
            torrent(PAINTER,t,age,time,camera,visibility(mc.level.getEntity(t.caster)));
        }
        PAINTER.flush(pose,buffers);
    }

    /**
     * How strongly to draw the effect for whoever is watching it.
     *
     * <p>Held at arm's length, this thing grows well past the distance between a caster's eyes and their
     * own hands, so in first person the camera would end up inside the membrane and the screen would go
     * white at exactly the moment the player most needs to aim. Their own charge is therefore dimmed and
     * its centre pushed out ahead of them; everybody else sees it in full.
     */
    private static float visibility(Entity caster) {
        var mc=Minecraft.getInstance();
        return caster!=null&&caster==mc.player&&mc.options.getCameraType().isFirstPerson()?.18f:1;
    }

    /**
     * The contained sphere: a membrane that stops being a sphere as the pressure rises, an ocean of
     * currents inside it, braids over it and arcs jumping across it.
     *
     * @param scale 1 while charging; driven down through the release compression.
     */
    private static void sphere(BranchVfx.Painter painter,Entity caster,int held,double radius,double scale,
                              double time,double distanceSq,float partial,float visibility) {
        RenderType glow=BranchVfx.glow(),strand=BranchVfx.strand(),cloud=BranchVfx.cloud(),shadow=BranchVfx.shadow();
        int stage=BranchCharge.stage(held);
        float power=BranchCharge.power(held);
        float over=BranchCharge.overcharge(held);
        double base=radius*scale;
        if(base<=.02)return;
        // Instability by stage, exactly as the charge is documented: calm, then obvious, then struggling.
        double chaos=switch(stage) {case 1->.05;case 2->.12;case 3->.23;case 4->.36;case 5->.47;default->.47+over*.26;};
        double surge=1+.10*Math.sin(time*.31)+(stage>=4?.09*Math.sin(time*.83+1.4):0)+over*.08*Math.sin(time*1.7);
        Vec3 centre=BranchCharge.focus(caster,partial);
        // Kept ahead of the caster's own eye as it grows, so their view is never inside the membrane.
        if(visibility<1)centre=centre.add(BranchCharge.aim(caster,partial).scale(Math.max(0,base*1.25-.9)));
        // The centre of mass shifts: an overfilled membrane does not stay concentric with its contents.
        Vec3 drift=new Vec3(BranchVfx.wobble(time*.07,1.3,2.7),BranchVfx.wobble(2.1,time*.06,.4),
                            BranchVfx.wobble(.9,3.3,time*.08)).scale(base*chaos*.42);
        centre=centre.add(drift);
        boolean localFirst=visibility<.5f;
        chargeAura(painter,caster,centre,base,time,partial,localFirst,visibility);
        if(localFirst) {
            // Keep the caster's crosshair readable: compact black mass around the hands/focus only.
            BranchVfx.billboard(painter,shadow,centre,Math.max(.12,base*.22),time*.05,
                TimeBranchPalette.shadow((float)(time*.03)),.58f);
            BranchVfx.billboard(painter,glow,centre,Math.max(.05,base*.08),-time*.08,
                TimeBranchPalette.hot((float)(time*.04),.68f),.34f);
            return;
        }
        boolean far=distanceSq>48*48;
        int rings=Math.max(4,Math.min(13,3+stage*2)-(far?3:0));
        int sides=Math.max(6,Math.min(18,7+stage*2)-(far?5:0));

        // --- the membrane -------------------------------------------------------------
        for(int r=0;r<rings;r++) {
            double t0=r/(double)rings,t1=(r+1)/(double)rings;
            double th0=t0*Math.PI,th1=t1*Math.PI;
            for(int s=0;s<sides;s++) {
                double p0=s*Math.PI*2/sides,p1=(s+1)*Math.PI*2/sides;
                Vec3 a=skin(centre,base,surge,chaos,th0,p0,time);
                Vec3 b=skin(centre,base,surge,chaos,th0,p1,time);
                Vec3 c=skin(centre,base,surge,chaos,th1,p1,time);
                Vec3 d=skin(centre,base,surge,chaos,th1,p0,time);
                float phase=(float)(time*.034+t0*.42+s/(double)sides*.3);
                double stretch=a.distanceTo(centre)/Math.max(1e-6,base);
                // Where the membrane is thinnest it glows hardest; that is the pressure showing.
                float heat=(float)Mth.clamp((stretch-1)*1.4,0,.85);
                painter.quad(shadow,a,b,c,d,TimeBranchPalette.shadow(phase),
                    (float)((.24+.12*power+.08*heat+over*.04)*visibility));
                if(heat>.08f)painter.quad(glow,a,b,c,d,TimeBranchPalette.hot(phase,heat*.72f),
                    (float)((.018+.025*heat)*visibility));
            }
        }

        // --- the core -----------------------------------------------------------------
        for(int i=0;i<3;i++) {
            double pulse=.30+.12*Math.sin(time*.27+i*2.1)+(stage>=5?.06*Math.sin(time*1.1):0);
            BranchVfx.billboard(painter,glow,centre,base*pulse*(1+i*.45),time*.05+i,
                TimeBranchPalette.hot((float)(time*.09+i*.2),i==0?.92f:.45f),(i==0?.55f:.20f)*visibility);
        }

        // --- the nebula it is packed in -----------------------------------------------
        int puffs=far?3:4+stage*3;
        for(int i=0;i<puffs;i++) {
            double a=i*2.399963+time*.021,h=(TemporalLightning.rand(i,7)-.5)*2;
            double rr=base*(.55+.9*TemporalLightning.rand(i,11));
            Vec3 at=centre.add(Math.cos(a)*rr,h*rr*.8,Math.sin(a)*rr);
            BranchVfx.billboard(painter,shadow,at,base*(.75+.55*TemporalLightning.rand(i,13)),a*.5,
                TimeBranchPalette.shadow((float)(time*.019+TemporalPalette.offset(i))),(.20f+.07f*power)*visibility);
            if((i&3)==0)BranchVfx.billboard(painter,cloud,at,base*.34,a,
                TimeBranchPalette.shade((float)(time*.026+i*.11)),.055f*visibility);
        }

        // --- braided strands ----------------------------------------------------------
        int strands=far?2:2+stage;
        for(int s=0;s<strands;s++) {
            double tilt=TemporalPalette.offset(s)*Math.PI*2+time*.028*(s%2==0?1:-1.35);
            Vec3 axis=new Vec3(Math.cos(tilt),Math.sin(tilt*.7),Math.sin(tilt)).normalize();
            Vec3 u=BranchVfx.perpendicular(axis),v=u.cross(axis).normalize();
            int steps=far?8:14;
            Vec3[] points=new Vec3[steps+1];
            for(int i=0;i<=steps;i++) {
                double a=i/(double)steps*Math.PI*2;
                double wob=1+chaos*.85*BranchVfx.wobble(a*1.9+s*3.1,time*.13,s*2.3);
                points[i]=centre.add(u.scale(Math.cos(a)).add(v.scale(Math.sin(a))).scale(base*1.02*wob*surge));
            }
            BranchVfx.branchPolyline(painter,strand,points,base*(.05+.03*power),
                (float)(time*.04+TemporalPalette.offset(s)),.55f,(.42f+.22f*power)*visibility);
        }

        // --- tiny branching filaments off the surface ---------------------------------
        if(!far)for(int i=0;i<stage;i++) {
            long seed=(long)(time/TemporalLightning.FLICKER)*31+i;
            Vec3 from=skin(centre,base,surge,chaos,TemporalLightning.rand(seed,1)*Math.PI,
                TemporalLightning.rand(seed,2)*Math.PI*2,time);
            Vec3 out=from.subtract(centre);
            if(out.lengthSqr()<1e-8)continue;
            Vec3 to=from.add(out.normalize().scale(base*(.35+.5*TemporalLightning.rand(seed,3))));
            TemporalLightning.drawBranch(painter,strand,TemporalLightning.bolt(seed,from,to,3,base*.14,1),
                base*.022,(float)(time*.05+i*.2),.5f*visibility);
        }

        // --- arcs across the sphere, along the arms, and out at the world -------------
        int arcs=far?Math.max(0,stage-3):Math.max(0,stage-1);
        for(int i=0;i<arcs;i++) {
            long seed=(long)(time/TemporalLightning.FLICKER)*97+i*13;
            Vec3 from=skin(centre,base,surge,chaos,TemporalLightning.rand(seed,4)*Math.PI,TemporalLightning.rand(seed,5)*Math.PI*2,time);
            Vec3 to=skin(centre,base,surge,chaos,TemporalLightning.rand(seed,6)*Math.PI,TemporalLightning.rand(seed,7)*Math.PI*2,time);
            TemporalLightning.drawBranch(painter,strand,TemporalLightning.bolt(seed,from,to,5,base*.30,1),
                base*.028,(float)(time*.06+i*.3),.62f*visibility);
        }
        if(stage>=3&&!far) {
            // Up the arms: the containment is not only in front of them, it is running over them.
            Vec3 side=BranchVfx.perpendicular(BranchCharge.aim(caster,partial));
            for(int hand=0;hand<2;hand++) {
                long seed=(long)(time/TemporalLightning.FLICKER)*61+hand;
                Vec3 shoulder=caster.getEyePosition(partial).add(side.scale(hand==0?.34:-.34)).add(0,-.28,0);
                TemporalLightning.drawBranch(painter,strand,TemporalLightning.bolt(seed,shoulder,centre,4,base*.22,1),
                    base*.020,(float)(time*.07+hand*.5),.44f*visibility);
            }
        }
        if(stage>=4&&!far) {
            // Reaching for something solid and snapping back. One a frame, never a spray.
            long seed=(long)(time/TemporalLightning.FLICKER)*151;
            double a=TemporalLightning.rand(seed,20)*Math.PI*2,tilt=(TemporalLightning.rand(seed,21)-.5)*Math.PI;
            Vec3 outward=new Vec3(Math.cos(a)*Math.cos(tilt),Math.sin(tilt),Math.sin(a)*Math.cos(tilt));
            Vec3 to=centre.add(outward.scale(base*(2.2+2.4*TemporalLightning.rand(seed,22))));
            TemporalLightning.drawBranch(painter,strand,TemporalLightning.bolt(seed,centre,to,6,base*.42,2),
                base*.020,(float)(time*.05),.38f*visibility);
        }

        // --- space giving way around it ------------------------------------------------
        if(stage>=3&&!far)for(int i=0;i<2;i++) {
            double breathe=1+.09*Math.sin(time*.19+i*2.3);
            BranchVfx.billboard(painter,cloud,centre,base*(2.1+i*.7)*breathe,time*.013*(i==0?1:-1),
                TimeBranchPalette.shade((float)(time*.012+i*.4)),(.035f+.02f*power)*visibility);
        }
    }

    /** Flight-nebula language around the head and hands while the held attack charges. */
    private static void chargeAura(BranchVfx.Painter painter,Entity caster,Vec3 focus,double base,double time,
                                   float partial,boolean localFirst,float visibility) {
        RenderType shadow=BranchVfx.shadow(),cloud=BranchVfx.cloud(),glow=BranchVfx.glow();
        Vec3 look=BranchCharge.aim(caster,partial);
        Vec3 side=BranchVfx.perpendicular(look);
        Vec3 hands=focus.add(0,-.10,0);
        for(int hand=-1;hand<=1;hand+=2) {
            Vec3 handAt=hands.add(side.scale(hand*.24));
            for(int i=0;i<3;i++) {
                double a=time*.035*(i%2==0?1:-1)+i*2.1+hand;
                Vec3 at=handAt.add(Math.cos(a)*(.12+i*.035),Math.sin(a*1.3)*.08,Math.sin(a)*(.12+i*.035));
                BranchVfx.billboard(painter,shadow,at,.24+i*.055,a,
                    TimeBranchPalette.shadow((float)(time*.025+i*.17)),localFirst?.34f:.54f*visibility);
            }
            BranchVfx.billboard(painter,glow,handAt,.075+base*.018,time*.08*hand,
                TimeBranchPalette.hot((float)(time*.045+hand*.12),.62f),localFirst?.30f:.42f*visibility);
        }
        if(localFirst)return;
        Vec3 head=caster.getEyePosition(partial).add(0,.10,0);
        for(int i=0;i<7;i++) {
            double a=i*2.399963+time*.022*(i%2==0?1:-.7);
            Vec3 at=head.add(Math.cos(a)*(.28+i*.018),(i-3)*.085+Math.sin(a)*.05,Math.sin(a)*(.28+i*.018));
            BranchVfx.billboard(painter,shadow,at,.34+i*.025,a*.5,
                TimeBranchPalette.shadow((float)(time*.018+i*.11)),.42f*visibility);
            if((i&2)==0)BranchVfx.billboard(painter,cloud,at,.16,a,
                TimeBranchPalette.shade((float)(time*.026+i*.13)),.055f*visibility);
        }
    }

    /** One point on the deformed membrane. Several travelling lobes, not one smooth sphere. */
    private static Vec3 skin(Vec3 centre,double base,double surge,double chaos,double theta,double phi,double time) {
        double x=Math.sin(theta)*Math.cos(phi),y=Math.cos(theta),z=Math.sin(theta)*Math.sin(phi);
        double lobe=BranchVfx.wobble(x*2.1+time*.085,y*2.1,z*2.1+time*.061);
        double slam=BranchVfx.wobble(x*4.3,y*4.3+time*.14,z*4.3);
        double ripple=Math.sin(theta*5.1+phi*3.3-time*.27)*.35;
        double r=base*surge*(1+chaos*(lobe*.72+slam*.38+ripple));
        return centre.add(x*r,y*r,z*r);
    }

    /**
     * The torrent: rings down the axis with strands braided around them, branches leaving and rejoining,
     * pulses travelling through, and a far end that comes apart into filaments rather than stopping flat.
     */
    private static void torrent(BranchVfx.Painter painter,Torrent t,double age,double time,Vec3 camera,float visibility) {
        RenderType glow=BranchVfx.glow(),strand=BranchVfx.strand(),cloud=BranchVfx.cloud(),shadow=BranchVfx.shadow();
        // Every layer below multiplies this, so dimming the caster's own first-person view is one change.
        float fade=(float)Mth.clamp((t.life-age)/BranchCharge.FADE,0,1)*visibility;
        double front=BranchCharge.front(t.length,age);

        // The held breath: the sphere collapses almost to nothing, and for a moment nothing happens.
        if(age<BranchCharge.COMPRESS+.6) {
            Entity caster=Minecraft.getInstance().level.getEntity(t.caster);
            double collapse=Math.max(.06,1-age/BranchCharge.COMPRESS);
            if(caster!=null)
                sphere(painter,caster,t.held,BranchCharge.sphere(t.held),
                    collapse*collapse,time,caster.position().distanceToSqr(camera),1,visibility);
            // Compressed to a point and far brighter than it was: all of it, in a thumbnail.
            BranchVfx.billboard(painter,glow,t.origin,BranchCharge.sphere(t.held)*(collapse*.5+.12),time*.2,
                TimeBranchPalette.hot((float)(time*.13),.95f),(float)((.5+.5*(1-collapse))*visibility));
        }
        if(front<=0)return;

        boolean far=t.origin.distanceToSqr(camera)>64*64;
        double core=BranchCharge.beamRadius(t.power);
        int rings=(int)Mth.clamp(front/(far?3.6:1.7)+2,3,far?24:56);
        Vec3[] centres=new Vec3[rings+1];
        double[] wide=new double[rings+1],thin=new double[rings+1],haze=new double[rings+1];
        int[] colour=new int[rings+1];
        float[] alpha=new float[rings+1],hotAlpha=new float[rings+1],hazeAlpha=new float[rings+1];
        int[] hot=new int[rings+1];
        double span=Math.max(.001,front-BranchCharge.SAFE);
        for(int i=0;i<=rings;i++) {
            double t01=i/(double)rings;
            double distance=BranchCharge.SAFE+span*t01;
            // Not a cylinder: it opens out of the hands, breathes along its length and frays at the end.
            double open=Mth.clamp(distance/(BranchCharge.SAFE+3.5),0,1);
            double breathe=1+.15*Math.sin(distance*.85-age*.9)+.11*BranchVfx.wobble(distance*.4,age*.12,t01*3.1);
            double flare=front>=t.length-.02?1+Math.pow(Mth.clamp((t01-.82)/.18,0,1),2)*1.5:1;
            double radius=core*(.42+.72*open)*breathe*flare;
            // Pulses of light running down it, which is what makes it read as a flow and not a beam.
            double pulse=0;
            for(int k=0;k<3;k++) {
                double at=(age*1.7+k*7.3)%(t.length+6)-3;
                pulse+=Math.exp(-Math.pow(distance-at,2)*.45);
            }
            centres[i]=t.origin.add(t.direction.scale(distance))
                .add(BranchVfx.perpendicular(t.direction).scale(BranchVfx.wobble(distance*.23,age*.07,1.7)*core*.11));
            wide[i]=radius*1.22;thin[i]=radius*.33;haze[i]=radius*3.0;
            float phase=(float)(time*.036+distance*.028);
            colour[i]=TimeBranchPalette.shade(phase);
            hot[i]=TimeBranchPalette.hot(phase+.1f,(float)Mth.clamp(.5+pulse,0,1));
            alpha[i]=(float)((.70+.12*t.power)*fade*(1+pulse*.10));
            hotAlpha[i]=(float)((.34+.14*t.power)*fade*(1+pulse*.35));
            hazeAlpha[i]=(float)((.22+.08*t.power)*fade);
        }
        int sides=far?6:(t.power>.6f?12:9);
        Vec3 u=BranchVfx.perpendicular(t.direction),v=u.cross(t.direction).normalize();
        // Three nested volumes of cloud before any of the sharp material goes on: an outer bank that the
        // torrent is buried in, the body of the haze, and the lit inner shell the branches show through.
        double[] outer=new double[rings+1];
        float[] outerAlpha=new float[rings+1];
        for(int i=0;i<=rings;i++){outer[i]=haze[i]*1.55;outerAlpha[i]=hazeAlpha[i]*.62f;}
        BranchVfx.tube(painter,shadow,centres,outer,colour,outerAlpha,Math.max(5,sides-4),-.04);
        BranchVfx.tube(painter,shadow,centres,haze,colour,hazeAlpha,Math.max(5,sides-3),.06);
        BranchVfx.tube(painter,shadow,centres,wide,colour,alpha,sides,.11);
        BranchVfx.tube(painter,glow,centres,thin,hot,hotAlpha,Math.max(5,sides-3),-.07);

        // Banks of cloud rolling along it, turning at their own rates, so the volume churns instead of
        // sitting there as smooth tubes. These are what make it read as nebula rather than as a pipe.
        int banks=far?4:10+(int)(t.power*14);
        for(int b=0;b<banks;b++) {
            double t01=(b+.5)/banks;
            double distance=BranchCharge.SAFE+span*t01;
            double a=TemporalPalette.offset(b)*Math.PI*2+age*.05*(b%2==0?1:-1);
            double ring=core*(.9+1.5*TemporalLightning.rand(b,3));
            Vec3 at=t.origin.add(t.direction.scale(distance))
                .add(u.scale(Math.cos(a)*ring)).add(v.scale(Math.sin(a)*ring))
                .add(t.direction.scale(BranchVfx.wobble(b,age*.09,t01*4)*1.4));
            BranchVfx.billboard(painter,shadow,at,core*(1.9+2.1*TemporalLightning.rand(b,5)),a*.6+age*.02,
                TimeBranchPalette.shadow((float)(time*.017+TemporalPalette.offset(b))),
                (.24f+.09f*t.power)*fade);
            if((b&2)==0)BranchVfx.billboard(painter,cloud,at,core*(.65+.45*TemporalLightning.rand(b,8)),a,
                TimeBranchPalette.shade((float)(time*.027+b*.09)),.055f*fade);
        }

        // --- timeline branches wound around it ----------------------------------------
        int strands=far?3:4+(int)(t.power*5);
        int steps=far?10:Math.min(40,rings+2);
        for(int s=0;s<strands;s++) {
            double phase=TemporalPalette.offset(s)*Math.PI*2;
            double coil=.55+.25*TemporalLightning.rand(s,3);
            double split=.28+.34*TemporalLightning.rand(s,5);
            Vec3[] points=new Vec3[steps+1];
            for(int i=0;i<=steps;i++) {
                double t01=i/(double)steps;
                double distance=BranchCharge.SAFE+span*t01;
                double a=phase+distance*coil-age*.28;
                // One branch in three leaves the torrent for a while and comes back into it further on.
                double away=1+1.45*Math.exp(-Math.pow((t01-split)/.085,2))*(s%3==0?1:.25);
                double swell=core*(.45+.62*Mth.clamp(distance/(BranchCharge.SAFE+3.5),0,1))*away;
                points[i]=t.origin.add(t.direction.scale(distance))
                    .add(u.scale(Math.cos(a)).add(v.scale(Math.sin(a))).scale(swell));
            }
            BranchVfx.branchPolyline(painter,strand,points,core*(.055+.03*t.power),
                (float)(time*.045+TemporalPalette.offset(s)),.7f,(.40f+.22f*t.power)*fade);
        }

        // --- arcs running along the outside -------------------------------------------
        int arcs=far?1:2+(int)(t.power*4);
        for(int i=0;i<arcs;i++) {
            long seed=(long)(age/TemporalLightning.FLICKER)*211+i*29;
            double d0=BranchCharge.SAFE+TemporalLightning.rand(seed,1)*span*.86;
            double d1=Math.min(front,d0+2.5+TemporalLightning.rand(seed,2)*5);
            double a0=TemporalLightning.rand(seed,3)*Math.PI*2,a1=a0+(TemporalLightning.rand(seed,4)-.5)*2.2;
            Vec3 from=t.origin.add(t.direction.scale(d0)).add(u.scale(Math.cos(a0)).add(v.scale(Math.sin(a0))).scale(core*1.2));
            Vec3 to=t.origin.add(t.direction.scale(d1)).add(u.scale(Math.cos(a1)).add(v.scale(Math.sin(a1))).scale(core*1.35));
            TemporalLightning.drawBranch(painter,strand,TemporalLightning.bolt(seed,from,to,5,core*.42,2),
                core*.034,(float)(time*.05+i*.2),.52f*fade);
        }

        // --- the muzzle ----------------------------------------------------------------
        Vec3 muzzle=t.origin.add(t.direction.scale(BranchCharge.SAFE*.55));
        BranchVfx.billboard(painter,shadow,muzzle,core*(1.45+.18*Math.sin(time*.4)),time*.08,
            TimeBranchPalette.shadow((float)(time*.03)),.74f*fade);
        BranchVfx.billboard(painter,glow,muzzle,core*(.48+.08*Math.sin(time*.55)),-time*.1,
            TimeBranchPalette.hot((float)(time*.08),.82f),.42f*fade);
        if(front<t.length-.02) {
            Vec3 head=t.origin.add(t.direction.scale(front));
            double beat=1+.09*Math.sin(time*.42);
            BranchVfx.billboard(painter,shadow,head,core*1.55*beat,time*.035,
                TimeBranchPalette.shadow((float)(time*.025)),.86f*fade);
            BranchVfx.billboard(painter,glow,head,core*.66*beat,-time*.07,
                TimeBranchPalette.hot((float)(time*.055),.78f),.48f*fade);
        }

        // --- the far end coming apart ---------------------------------------------------
        if(front>=t.length-.02) {
            Vec3 head=t.origin.add(t.direction.scale(t.length));
            int filaments=far?5:12+(int)(t.power*10);
            for(int i=0;i<filaments;i++) {
                double a=i*2.399963+age*.06;
                double spread=.25+.65*TemporalLightning.rand(i,9);
                int hops=far?3:5;
                Vec3[] points=new Vec3[hops+1];
                for(int k=0;k<=hops;k++) {
                    double t01=k/(double)hops;
                    // Each filament leaves the axis as it goes, so the stream unravels rather than stops.
                    double out=core*spread*t01*t01*6.5;
                    points[k]=head.add(t.direction.scale(t01*(2.4+t.power*2.6)))
                        .add(u.scale(Math.cos(a)).add(v.scale(Math.sin(a))).scale(out))
                        .add(t.direction.scale(BranchVfx.wobble(i,t01*3,age*.1)*.3));
                }
                BranchVfx.branchPolyline(painter,strand,points,core*.05,
                    (float)(time*.05+TemporalPalette.offset(i)),.9f,.40f*fade);
            }
            for(int i=0;i<(far?2:5);i++) {
                double a=i*2.399963-age*.04;
                Vec3 at=head.add(t.direction.scale(1.2+i*.7)).add(u.scale(Math.cos(a)*core*(.8+i*.4)))
                    .add(v.scale(Math.sin(a)*core*(.8+i*.4)));
                BranchVfx.billboard(painter,cloud,at,core*(1.3+i*.5),a,
                    TimeBranchPalette.shade((float)(time*.02+i*.3)),.075f*fade);
            }
        }

        // --- soft cover coming apart ----------------------------------------------------
        dissolve(painter,t,age,time,camera,fade);
    }

    /**
     * Each doomed block is engulfed before it goes: brightening fragments lifted off it and swept down
     * the torrent, so by the time the server takes the block there is nothing left to see leaving.
     */
    private static void dissolve(BranchVfx.Painter painter,Torrent t,double age,double time,Vec3 camera,float fade) {
        RenderType glow=BranchVfx.glow(),strand=BranchVfx.strand();
        int drawn=0;
        for(int i=0;i<t.volume.size()&&drawn<MAX_DISSOLVE_DRAWN;i++) {
            double opens=BranchCharge.reaches(t.reach.get(i));
            if(opens>age)break;
            double phase=(age-opens)/BranchCharge.DISSOLVE;
            if(phase>1)continue;
            BlockPos pos=t.volume.get(i);
            Vec3 at=Vec3.atCenterOf(pos);
            if(at.distanceToSqr(camera)>3600)continue;
            drawn++;
            float grow=(float)Mth.clamp(phase*1.4,0,1);
            // The block's own volume, lit from inside and shrinking as it is taken.
            BranchVfx.billboard(painter,glow,at,.52*(1-phase*.55),time*.04+i,
                TimeBranchPalette.hot((float)(time*.05+i*.13),grow*.7f),(.22f+.30f*grow)*fade);
            int shards=phase<.5?3:2;
            for(int k=0;k<shards;k++) {
                double spin=TemporalLightning.rand(i*7+k,1)*Math.PI*2;
                double lift=phase*(.35+TemporalLightning.rand(i*7+k,2)*.55);
                Vec3 fragment=at.add(Math.cos(spin)*.34,TemporalLightning.rand(i*7+k,3)*.5-.25,Math.sin(spin)*.34)
                    .add(t.direction.scale(lift*2.6));
                BranchVfx.billboard(painter,glow,fragment,.09*(1-phase*.7),spin+time*.12,
                    TimeBranchPalette.shade((float)(time*.06+TemporalPalette.offset(i+k))),(1-(float)phase)*.55f*fade);
            }
            if(phase>.25&&(i&3)==0) {
                long seed=(long)(age/TemporalLightning.FLICKER)*17+i;
                Vec3 to=at.add(t.direction.scale(.9)).add(0,.4,0);
                TemporalLightning.drawBranch(painter,strand,TemporalLightning.bolt(seed,at,to,3,.22,1),.018,
                    (float)(time*.05),.34f*fade);
            }
        }
    }
}
