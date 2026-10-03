package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.entity.ThrownDagger;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Bleeding, seen rather than counted.
 *
 * <p>Wounds spatter from the blade that made them, not from an entity's centre, so a body with two
 * daggers in it bleeds from two places. A body that keeps moving leaves that blood behind it: small
 * pools are laid on whatever surface is actually underneath, they spread for a moment and then dry
 * out. Everything is bounded — a fixed pool budget, a view-distance cut, emission gated on movement
 * and stack count — so a long fight stains the ground without flooding the particle engine.
 *
 * <p>A Scepter beam's hole bleeds far harder than any blade: it pours from the hole itself in a steady
 * stream, spurts with every heartbeat, and pools under the body into a spreading puddle that lies there
 * long after, a red trail behind anything that walks on with it.
 *
 * <p>What lies on the ground is {@link BloodStains}': puddles fed by every wound here (one under a body that bleeds
 * where it stands, widening as it goes on), and the marks the drops themselves leave where they come down
 * (HexParticles.Drip calls {@link #landed}), so the spatter on the ground follows the sprays in the air.
 */
public final class Blood {
    private Blood() {}

    private static final Map<Integer,Vec3> LAST=new HashMap<>();
    /** How long a drop's mark and a small pool lie, and a puddle, after blood last ran into it. */
    private static final int LIFE=600,PUDDLE_LIFE=1200;
    private static final double RANGE=1024;
    /** Ticks between the spurts of a beam's hole: a racing heart. */
    private static final int BEAT=18;

    /** Bodies bleeding from a Scepter beam's hole, by id. */
    private static final Set<Integer> POURING=new HashSet<>();

    public static void pouring(int entity,boolean pouring) {
        if(pouring){if(POURING.size()<256)POURING.add(entity);}
        else POURING.remove(entity);
    }

    /** Bodies a partial Scepter beam left on their last breath, by id, with the tick they fall. */
    private static final Map<Integer,Long> STANDING=new HashMap<>();

    public static void clear() {BloodStains.clear();LAST.clear();STANDING.clear();POURING.clear();}

    public static void lastMoments(int entity,long until) {
        if(until>ClientState.now()){if(STANDING.size()<128)STANDING.put(entity,until);}
        else STANDING.remove(entity);
    }

    /**
     * @param wounds embedded blades by the id of the body carrying them, gathered once by the caller's
     *               single pass over the visible entities.
     */
    public static void tick(Minecraft mc,Map<Integer,Integer> bleeding,Map<Integer,List<ThrownDagger>> wounds) {
        if(mc.level==null||mc.player==null)return;
        long now=ClientState.now();
        BloodStains.tick(mc.level);
        standing(mc,now);
        POURING.retainAll(bleeding.keySet());
        if(bleeding.isEmpty()){LAST.clear();return;}
        Vec3 eye=mc.player.getEyePosition();
        var random=mc.level.random;
        Set<Integer> seen=new HashSet<>();
        for(var entry:bleeding.entrySet()) {
            Entity e=mc.level.getEntity(entry.getKey());
            if(e==null||e.position().distanceToSqr(eye)>RANGE)continue;
            seen.add(entry.getKey());
            int stacks=Mth.clamp(entry.getValue(),1,5);
            List<ThrownDagger> blades=wounds.getOrDefault(entry.getKey(),List.of());

            // Spatter: from each blade if any are still in, otherwise from the body itself.
            for(int i=0;i<stacks;i++) {
                if(random.nextInt(2)!=0)continue;
                Vec3 at=blades.isEmpty()
                    ?e.position().add((random.nextDouble()-.5)*e.getBbWidth(),e.getBbHeight()*(.35+random.nextDouble()*.4),(random.nextDouble()-.5)*e.getBbWidth())
                    :WoundAnchor.world(blades.get(random.nextInt(blades.size())),e,1);
                Vfx.spark(HexGodOfStories.BLOOD.get(),at,new Vec3((random.nextDouble()-.5)*.03,-.04,(random.nextDouble()-.5)*.03));
            }

            Vec3 previous=LAST.put(entry.getKey(),e.position());
            if(previous==null)continue;
            boolean moving=previous.distanceToSqr(e.position())>.0016;
            if(POURING.contains(entry.getKey())){pour(mc,e,now,moving);continue;}
            // A still body pools where it stands, the one puddle widening; a running one leaves a trail of small ones
            // behind it (the drops falling from the wound leave their own marks as they land).
            int odds=moving?Math.max(2,7-stacks):10;
            if(random.nextInt(odds)!=0)continue;
            if(moving)drop(mc,e,null,random.nextDouble()*.12+.1,LIFE,10);
            else drop(mc,e,null,random.nextDouble()*.2+.22,PUDDLE_LIFE,30);
        }
        LAST.keySet().retainAll(seen);
    }

    /**
     * Gravity Grasp's cut, across the throat: as the blade comes through, a great sheet of blood thrown out to the side it
     * went over three ticks, pooling all along where it lands; then the neck pumping on in heavy spurts, each weaker than
     * the last, and running down the body between them, for three seconds.
     */
    public static void throat(Entity e,Vec3 right) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||e.position().distanceToSqr(mc.player.getEyePosition())>RANGE)return;
        var random=mc.level.random;
        Vec3 side=right.lengthSqr()<1e-6?new Vec3(1,0,0):right.normalize(),neck=e.position().add(0,e.getBbHeight()*.8,0);
        Vfx.bloom(e.getId(),neck,side,60,(at,aim,t)->{
            int age=Math.round(t*60);
            // Running down the body all the while.
            for(int i=0;i<Math.round(6*(1-t))+1;i++)
                Vfx.spark(HexGodOfStories.BLOOD.get(),at.add((random.nextDouble()-.5)*.2,(random.nextDouble()-.5)*.12,(random.nextDouble()-.5)*.2),
                    new Vec3(aim.x*.02+(random.nextDouble()-.5)*.03,-.05-random.nextDouble()*.05,aim.z*.02+(random.nextDouble()-.5)*.03));
            if(age>=3&&age<=5) {
                // Flung off the blade as it comes through: a long, heavy sheet thrown out to the right, from all across the cut.
                for(int i=0;i<(age==3?300:age==4?160:70);i++) {
                    Vec3 cut=at.add(aim.scale(e.getBbWidth()*(-.3+random.nextDouble()*.7))).add(0,(random.nextDouble()-.5)*.14,0);
                    double speed=.18+random.nextDouble()*.5;
                    Vfx.spark(HexGodOfStories.BLOOD.get(),cut,aim.scale(speed).add((random.nextDouble()-.5)*.18,.03+random.nextDouble()*.22,(random.nextDouble()-.5)*.18));
                }
                for(int i=0;i<(age==3?22:age==4?10:4);i++)
                    drop(mc,e,at.add(aim.scale(.5+random.nextDouble()*4)).add((random.nextDouble()-.5)*1.1,0,(random.nextDouble()-.5)*1.1),
                        random.nextDouble()*.45+.3,PUDDLE_LIFE,24);
            } else if(age>5&&(age-3)%4==0) {
                float left=1-t;
                for(int i=0;i<Math.round(45*left)+8;i++) {
                    double speed=(.07+random.nextDouble()*.2)*left;
                    Vfx.spark(HexGodOfStories.BLOOD.get(),at,aim.scale(speed).add((random.nextDouble()-.5)*.08,random.nextDouble()*.09,(random.nextDouble()-.5)*.08));
                }
                for(int i=0;i<3;i++)
                    drop(mc,e,at.add(aim.scale(.4+random.nextDouble()*1.2)).add((random.nextDouble()-.5)*.5,0,(random.nextDouble()-.5)*.5),
                        random.nextDouble()*.3+.2,PUDDLE_LIFE,30);
            }
            if(random.nextInt(2)==0)drop(mc,e,null,random.nextDouble()*.4+.35,PUDDLE_LIFE,40);
        });
    }

    /**
     * Gravity Grasp's stab, held in: as the knife goes in the blood gushes back out round it; for as long as it is left
     * in the neck the wound pumps round the blade with every beat, the blood pouring down the body and off the blade's
     * hilt into a spreading pool under it, and every time the knife is leaned on a heavy spurt is forced out past it. The
     * side it went in faces the one holding it.
     */
    public static void impale(Entity e,Entity by,int ticks) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||e.position().distanceToSqr(mc.player.getEyePosition())>RANGE)return;
        var random=mc.level.random;
        Vec3 toward=by==null?new Vec3(0,0,1):by.position().subtract(e.position());
        toward=new Vec3(toward.x,0,toward.z);
        toward=toward.lengthSqr()<1e-6?new Vec3(0,0,1):toward.normalize();
        final Vec3 out=toward;
        Vec3 wound=e.position().add(0,e.getBbHeight()*.82,0).add(out.scale(e.getBbWidth()*.45));
        Vfx.bloom(e.getId(),wound,out,Math.max(1,ticks),(at,aim,t)->{
            int age=Math.round(t*ticks);
            // A steady welling round the blade, pouring down the body.
            for(int i=0;i<12;i++)
                Vfx.spark(HexGodOfStories.BLOOD.get(),at.add((random.nextDouble()-.5)*.16,(random.nextDouble()-.5)*.12,(random.nextDouble()-.5)*.16),
                    new Vec3(aim.x*.025+(random.nextDouble()-.5)*.04,-.04-random.nextDouble()*.06,aim.z*.025+(random.nextDouble()-.5)*.04));
            // Going in: the blood gushes back out round the blade, toward the one driving it.
            if(age<=1) {
                for(int i=0;i<90;i++) {
                    double speed=.08+random.nextDouble()*.3;
                    Vec3 side=new Vec3(-aim.z,0,aim.x).scale((random.nextDouble()-.5)*1.8);
                    Vfx.spark(HexGodOfStories.BLOOD.get(),at,aim.add(side).normalize().scale(speed).add(0,.03+random.nextDouble()*.16,0));
                }
                for(int i=0;i<4;i++)
                    drop(mc,e,at.add(aim.scale(.3+random.nextDouble()*1.6)).add((random.nextDouble()-.5)*.7,0,(random.nextDouble()-.5)*.7),
                        random.nextDouble()*.3+.25,PUDDLE_LIFE,24);
            }
            // Each beat, and each push of the knife (tools/blade_moves.py: deeper on the ninth and nineteenth): a spurt forced out past it.
            boolean push=age==3||age==13,beat=age%4==0;
            if(push||beat) {
                for(int i=0;i<(push?150:50);i++) {
                    double speed=(push?.12:.06)+random.nextDouble()*(push?.3:.16);
                    Vec3 side=new Vec3(-aim.z,0,aim.x).scale((random.nextDouble()-.5)*1.4);
                    Vfx.spark(HexGodOfStories.BLOOD.get(),at,aim.add(side).normalize().scale(speed).add(0,.02+random.nextDouble()*.12,0));
                }
                for(int i=0;i<(push?5:2);i++)
                    drop(mc,e,at.add(aim.scale(.3+random.nextDouble()*(push?1.8:.7))).add((random.nextDouble()-.5)*.5,0,(random.nextDouble()-.5)*.5),
                        random.nextDouble()*.3+.22,PUDDLE_LIFE,24);
            }
            // Pooling under the body, a little wider every tick.
            drop(mc,e,null,random.nextDouble()*.4+.35,PUDDLE_LIFE,40);
        });
    }

    /**
     * Complete Evisceration's blade through a body: both wounds, where it went in and where its point comes out of the
     * back, pump with every beat, running down the body and spurting up and down it, and pool under it. Nothing is thrown
     * across the blade's line, which stays in plain sight.
     */
    public static void skewer(Entity e,Vec3 front,Vec3 back,int ticks) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||e.position().distanceToSqr(mc.player.getEyePosition())>RANGE)return;
        var random=mc.level.random;
        Vec3 through=back.subtract(front);
        Vfx.bloom(e.getId(),front,new Vec3(0,1,0),Math.max(1,ticks),(at,aim,t)->{
            int age=Math.round(t*ticks);
            for(Vec3 wound:new Vec3[]{at,at.add(through)}) {
                // Running down the body from it.
                for(int i=0;i<10;i++)
                    Vfx.spark(HexGodOfStories.BLOOD.get(),wound.add((random.nextDouble()-.5)*.2,(random.nextDouble()-.5)*.14,(random.nextDouble()-.5)*.2),
                        new Vec3((random.nextDouble()-.5)*.03,-.05-random.nextDouble()*.06,(random.nextDouble()-.5)*.03));
                // Each beat, a spurt up the body and a gush down it.
                if(age<=1||age%4==0) {
                    for(int i=0;i<44;i++) {
                        boolean up=i%2==0;
                        double speed=.08+random.nextDouble()*(up?.3:.18);
                        Vfx.spark(HexGodOfStories.BLOOD.get(),wound,new Vec3((random.nextDouble()-.5)*.09,up?speed:-speed,(random.nextDouble()-.5)*.09));
                    }
                    drop(mc,e,wound.add((random.nextDouble()-.5)*.7,0,(random.nextDouble()-.5)*.7),random.nextDouble()*.3+.25,PUDDLE_LIFE,24);
                }
            }
            drop(mc,e,null,random.nextDouble()*.4+.35,PUDDLE_LIFE,40);
        });
    }

    /**
     * A blade's cut or stab (BladeCombo, the ordinary attacks, the charge's slam, a thrown knife): a heavy sheet of
     * blood flung off the edge the way the blade went, the fastest drops thrown furthest; thick gobs arcing out of the
     * wound and a fine spray bursting from it; then the wound pumping two or three spurts after the blade has gone, and
     * pools spattered all along the way the blood was thrown and under the body.
     */
    public static void slash(int id,net.minecraft.nbt.CompoundTag n) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        Vec3 at=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        if(at.distanceToSqr(mc.player.getEyePosition())>RANGE)return;
        Vec3 swing=new Vec3(n.getDouble("dx"),n.getDouble("dy"),n.getDouble("dz"));
        if(swing.lengthSqr()<1e-6)return;
        swing=swing.normalize();
        float power=Math.max(.3f,Math.min(2.5f,n.getFloat("power")));
        // The one who cut feels it land: a kick of the view, the heavier the blade the harder.
        if(n.contains("by")&&n.getInt("by")==mc.player.getId())
            com.hexgodofstories.client.leviathan.LeviathanEffects.scepterRecoil(.22f+.3f*Math.min(1,power));
        var random=mc.level.random;
        // Across the cut, square to the swing: the line the edge opened.
        Vec3 across=swing.cross(new Vec3(0,1,0));
        if(across.lengthSqr()<1e-4)across=swing.cross(new Vec3(1,0,0));
        across=across.normalize();
        // The sheet off the edge.
        for(int i=0;i<Math.round(120*power);i++) {
            Vec3 from=at.add(across.scale((random.nextDouble()-.5)*.55)).add(swing.scale(random.nextDouble()*.15));
            double speed=(.12+random.nextDouble()*.46)*(.65+.35*power);
            Vfx.spark(HexGodOfStories.BLOOD.get(),from,swing.scale(speed).add((random.nextDouble()-.5)*.12,.03+random.nextDouble()*.14,(random.nextDouble()-.5)*.12));
        }
        // Gobs: slower, heavier, thrown up out of the wound to arc down and splash.
        for(int i=0;i<Math.round(26*power);i++) {
            double speed=.06+random.nextDouble()*.2;
            Vfx.spark(HexGodOfStories.BLOOD.get(),at.add(across.scale((random.nextDouble()-.5)*.3)),
                swing.scale(speed).add((random.nextDouble()-.5)*.1,.12+random.nextDouble()*.2,(random.nextDouble()-.5)*.1));
        }
        // A fine spray bursting out every way at once.
        for(int i=0;i<Math.round(36*power);i++)
            Vfx.spark(HexGodOfStories.BLOOD.get(),at,new Vec3((random.nextDouble()-.5)*.3,(random.nextDouble()-.3)*.22,(random.nextDouble()-.5)*.3));
        net.minecraft.world.entity.Entity e=mc.level.getEntity(id);
        if(e==null)return;
        // Spattered all along the way it was thrown, and pooling under the body.
        for(int i=0;i<Math.round(7*power)+3;i++)
            drop(mc,e,at.add(swing.scale(.3+random.nextDouble()*2.4*power)).add((random.nextDouble()-.5)*.8,0,(random.nextDouble()-.5)*.8),
                random.nextDouble()*.3+.16,LIFE,18);
        for(int i=0;i<2;i++)drop(mc,e,null,random.nextDouble()*.3+.3,PUDDLE_LIFE,40);
        // The wound pumps on after the blade has gone: a spurt or three, each weaker than the last.
        final Vec3 way=swing;
        final float strength=power;
        Vfx.bloom(id,at,way,22,(origin,aim,t)->{
            int age=Math.round(t*22);
            if(age!=5&&age!=12&&age!=19)return;
            float left=1-t*.6f;
            for(int i=0;i<Math.round(22*strength*left);i++) {
                double speed=(.08+random.nextDouble()*.2)*left;
                Vfx.spark(HexGodOfStories.BLOOD.get(),origin,aim.scale(speed).add((random.nextDouble()-.5)*.08,.05+random.nextDouble()*.12,(random.nextDouble()-.5)*.08));
            }
            drop(mc,e,origin.add(aim.scale(.4+random.nextDouble()*.9)).add((random.nextDouble()-.5)*.4,0,(random.nextDouble()-.5)*.4),
                random.nextDouble()*.22+.18,PUDDLE_LIFE,24);
        });
    }

    /**
     * A Scepter beam's hole, pouring: a stream from the hole itself, a spurt thrown out of it with every
     * heartbeat, and a puddle spreading under the body — or a trail behind it, pool after pool, if it moves.
     */
    private static void pour(Minecraft mc,Entity e,long now,boolean moving) {
        var random=mc.level.random;
        Vec3 hole=BeamWounds.bleedPoint(e);
        if(hole==null)hole=e.position().add(0,e.getBbHeight()*.6,0);
        // Out of the body, through the hole, level with the ground: the way a spurt is thrown.
        double ox=hole.x-e.getX(),oz=hole.z-e.getZ(),out=Math.sqrt(ox*ox+oz*oz);
        if(out<.05){double a=random.nextDouble()*Math.PI*2;ox=Math.cos(a);oz=Math.sin(a);out=1;}
        ox/=out;oz/=out;
        for(int i=0;i<3;i++)
            Vfx.spark(HexGodOfStories.BLOOD.get(),hole.add((random.nextDouble()-.5)*.06,(random.nextDouble()-.5)*.06,(random.nextDouble()-.5)*.06),
                new Vec3(ox*.02+(random.nextDouble()-.5)*.02,-.03-random.nextDouble()*.05,oz*.02+(random.nextDouble()-.5)*.02));
        if(Math.floorMod(now+e.getId()*7L,BEAT)==0) {
            for(int i=0;i<12;i++) {
                double speed=.1+random.nextDouble()*.14;
                Vfx.spark(HexGodOfStories.BLOOD.get(),hole,new Vec3(ox*speed+(random.nextDouble()-.5)*.06,.04+random.nextDouble()*.1,oz*speed+(random.nextDouble()-.5)*.06));
            }
            // Where the spurt comes down.
            for(int i=0;i<2;i++) {
                double reach=.5+random.nextDouble()*1.1;
                drop(mc,e,new Vec3(hole.x+ox*reach+(random.nextDouble()-.5)*.4,hole.y,hole.z+oz*reach+(random.nextDouble()-.5)*.4),
                    random.nextDouble()*.2+.18,PUDDLE_LIFE,24);
            }
        }
        // Under the hole it pools and spreads; on the move it is laid down behind, one pool after another.
        if(random.nextInt(moving?2:3)!=0)return;
        double spread=e.getBbWidth()*.35;
        drop(mc,e,new Vec3(hole.x+(random.nextDouble()-.5)*spread,hole.y,hole.z+(random.nextDouble()-.5)*spread),
            random.nextDouble()*.4+.4,PUDDLE_LIFE,moving?20:60);
    }

    /**
     * A body on its last breath pours from the hole the beam left in it and pools where it stands, far
     * faster than an ordinary wound, for as long as it stays on its feet.
     */
    private static void standing(Minecraft mc,long now) {
        if(STANDING.isEmpty())return;
        STANDING.entrySet().removeIf(entry->entry.getValue()<=now||mc.level.getEntity(entry.getKey())==null);
        Vec3 eye=mc.player.getEyePosition();
        var random=mc.level.random;
        for(int id:STANDING.keySet()) {
            Entity e=mc.level.getEntity(id);
            if(e==null||e.position().distanceToSqr(eye)>RANGE)continue;
            Vec3 at=BeamWounds.bleedPoint(e);
            if(at==null)at=e.position().add(0,e.getBbHeight()*.6,0);
            for(int i=0;i<2;i++)
                Vfx.spark(HexGodOfStories.BLOOD.get(),at.add((random.nextDouble()-.5)*.08,(random.nextDouble()-.5)*.08,(random.nextDouble()-.5)*.08),
                    new Vec3((random.nextDouble()-.5)*.05,-.02-random.nextDouble()*.04,(random.nextDouble()-.5)*.05));
            if(random.nextInt(3)==0)drop(mc,e,at,random.nextDouble()*.35+.3,PUDDLE_LIFE,40);
        }
    }

    /** A pool laid on whatever is under {@code at}, as a wound's: under a piece of a body cut in two (Halving), say. */
    public static void pool(Entity e,Vec3 at,double size) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||at.distanceToSqr(mc.player.getEyePosition())>RANGE)return;
        drop(mc,e,at,size,PUDDLE_LIFE,30);
    }

    /**
     * A drop come down on the ground (HexParticles.Drip): its mark, stretched the way it was going, or into the
     * puddle it fell in. Called for a share of the drops; a burst of hundreds lands as a spray of marks, not a carpet.
     */
    public static void landed(net.minecraft.world.level.Level level,double x,double y,double z,double vx,double vz,float size) {
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.player.distanceToSqr(x,y,z)>RANGE)return;
        BloodStains.mark(level,new Vec3(x,y,z),vx,vz,size*(1.3+level.random.nextFloat()*.9),LIFE);
    }

    /**
     * Blood run onto the surface below {@code over} (anywhere under the body when null), following steps and slabs
     * rather than assuming flat: into the puddle already there, which widens, or a new one spreading out over about
     * {@code spread} ticks (BloodStains).
     */
    private static void drop(Minecraft mc,Entity e,Vec3 over,double size,int life,int spread) {
        var random=mc.level.random;
        Vec3 from=over==null?e.position().add((random.nextDouble()-.5)*e.getBbWidth()*.8,.1,(random.nextDouble()-.5)*e.getBbWidth()*.8)
            :new Vec3(over.x,Math.min(over.y,e.getY()+.1),over.z);
        var hit=mc.level.clip(new ClipContext(from,from.add(0,-3,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,e));
        // Nothing below within reach, or the pool would start inside a wall a spurt was thrown against.
        if(hit.getType()==HitResult.Type.MISS||hit.isInside()||hit.getDirection()!=net.minecraft.core.Direction.UP)return;
        BloodStains.pool(mc.level,hit.getLocation(),size,life,spread);
    }
}
