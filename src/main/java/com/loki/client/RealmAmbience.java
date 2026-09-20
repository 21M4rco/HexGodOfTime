package com.loki.client;

import com.loki.Loki;
import com.loki.server.PocketRealm;
import com.loki.server.RealmShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The sanctum's own atmosphere: what the island looks like when nothing is happening.
 *
 * <p>Purely client side and deliberately thin. A handful of motes drift near the viewer, and the
 * island's edge carries a sparse nebula rim that flows slowly around the coast — enough to read as
 * a supernatural boundary, nowhere near enough to be a wall or a cost. Emission is randomised so it
 * never looks like a sprinkler: some ticks produce nothing at all.
 *
 * <p>Everything here is bounded before it is pretty. Only rim sections actually near the viewer are
 * sampled, a fixed handful of candidates is considered per tick rather than the whole perimeter,
 * the whole system idles outside the dimension, and the player's own particle setting is honoured.
 */
public final class RealmAmbience {
    private RealmAmbience() {}

    private static final double VIEW=52,VIEW_SQR=VIEW*VIEW;
    /** Rim candidates examined per tick. The rim is 288 blocks around; six is plenty at this range. */
    private static final int RIM_SAMPLES=6;
    private static final int MOTES=2;

    public static void tick() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        if(!PocketRealm.inside(mc.level))return;
        if(mc.options.particles().get()==ParticleStatus.MINIMAL)return;
        boolean sparse=mc.options.particles().get()==ParticleStatus.DECREASED;
        var random=mc.level.random;
        Vec3 eye=mc.player.getEyePosition();
        long now=ClientState.now();

        // Motes in the air around the viewer: rare, slow, and gone quickly.
        for(int i=0;i<(sparse?1:MOTES);i++) {
            if(random.nextInt(sparse?5:3)!=0)continue;
            Vec3 at=eye.add((random.nextDouble()-.5)*26,(random.nextDouble()-.35)*14,(random.nextDouble()-.5)*26);
            Vfx.spark(Loki.MOTE.get(),at,new Vec3((random.nextDouble()-.5)*.006,.004,(random.nextDouble()-.5)*.006));
        }
        // A rarer drifting fragment, and rarer still a small flicker of the island's own light.
        if(random.nextInt(sparse?60:26)==0) {
            Vec3 at=eye.add((random.nextDouble()-.5)*30,(random.nextDouble()-.2)*10,(random.nextDouble()-.5)*30);
            Vfx.spark(Loki.NEBULA.get(),at,new Vec3(0,.004,0));
        }
        if(random.nextInt(sparse?90:40)==0)
            Vfx.spark(Loki.STAR.get(),eye.add((random.nextDouble()-.5)*24,(random.nextDouble()-.1)*9,(random.nextDouble()-.5)*24),Vec3.ZERO);

        rim(mc,eye,now,sparse,random);
    }

    /**
     * The coast. Candidate angles walk slowly around the island so the band appears to flow, and any
     * candidate that is not close to the viewer is dropped before a particle is ever created.
     */
    private static void rim(Minecraft mc,Vec3 eye,long now,boolean sparse,net.minecraft.util.RandomSource random) {
        double originX=Mth.floor(eye.x)-PocketRealm.local(eye.x);
        double originZ=Mth.floor(eye.z)-PocketRealm.local(eye.z);
        double flow=now*.0016;
        for(int i=0;i<(sparse?3:RIM_SAMPLES);i++) {
            double angle=flow+random.nextDouble()*Math.PI*2;
            double reach=RealmShape.edge(angle);
            double lx=50+Math.cos(angle)*reach,lz=50+Math.sin(angle)*reach/1.06;
            double x=originX+lx,z=originZ+lz;
            double dx=x-eye.x,dz=z-eye.z;
            if(dx*dx+dz*dz>VIEW_SQR)continue;
            int surface=RealmShape.surface((int)Math.round(lx),(int)Math.round(lz));
            double base=PocketRealm.FLOOR_Y+surface;
            // A soft column at the lip: mostly haze, occasionally a wisp lifting off it.
            double y=base+random.nextDouble()*5-1.4;
            Vec3 at=new Vec3(x+(random.nextDouble()-.5)*2.4,y,z+(random.nextDouble()-.5)*2.4);
            Vec3 outward=new Vec3(Math.cos(angle),0,Math.sin(angle)).scale(.004);
            if(random.nextInt(3)==0)Vfx.spark(Loki.VEIL.get(),at,outward.add(0,.003,0));
            else Vfx.spark(Loki.MOTE.get(),at,outward.add(0,.002,0));
            // An occasional pulse: a brief brighter band, never a constant glow.
            if(random.nextInt(sparse?48:22)==0)
                Vfx.ring(Loki.NEBULA.get(),at,.9+random.nextDouble(),3,.01,.006);
        }
    }
}
