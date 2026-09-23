package com.hexgodofstories.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Cracks in the local flow of time, not electricity.
 *
 * <p>Vanilla's lightning bolt is never used anywhere in this ability. These are built here: thin,
 * rapidly branching arcs that jump between points on the sphere, run along the arms, reach briefly for
 * nearby surfaces and snap back, drawn in the same shifting temporal colours as everything else. They
 * are accents and they are budgeted like accents — a bolt is a dozen points, generated from a seed so
 * it holds still for the few ticks it exists instead of jittering every frame, and the whole ability
 * never has more than a couple of dozen of them alive.
 */
public final class TemporalLightning {
    private TemporalLightning() {}

    /** A spine with optional forks hanging off it. Positions are absolute, so it is built per use. */
    public record Arc(Vec3[] spine,List<Vec3[]> forks) {}

    /** Ticks a given bolt holds its shape before the seed rolls over and it is drawn anew. */
    public static final int FLICKER=2;

    /**
     * @param seed    anything stable for the life of this bolt; include {@code tick/FLICKER} to flicker.
     * @param chaos   sideways displacement in blocks at the middle of the span.
     * @param forks   how many secondary branches to hang off the spine.
     */
    public static Arc bolt(long seed,Vec3 from,Vec3 to,int segments,double chaos,int forks) {
        int n=Math.max(2,segments);
        Vec3 along=to.subtract(from);
        double span=along.length();
        if(span<1e-6)return new Arc(new Vec3[]{from,to},List.of());
        Vec3 axis=along.scale(1/span);
        Vec3 side=BranchVfx.perpendicular(axis),up=side.cross(axis).normalize();
        Vec3[] spine=new Vec3[n+1];
        spine[0]=from;spine[n]=to;
        for(int i=1;i<n;i++) {
            double t=i/(double)n;
            double taper=Math.sin(t*Math.PI);
            double a=(rand(seed,i*3)-.5)*2*chaos*taper;
            double b=(rand(seed,i*3+1)-.5)*2*chaos*taper;
            // A little forward jitter as well, so a bolt is not a flat zigzag on one plane.
            double c=(rand(seed,i*3+2)-.5)*chaos*.45*taper;
            spine[i]=from.add(axis.scale(span*t+c)).add(side.scale(a)).add(up.scale(b));
        }
        List<Vec3[]> branches=new ArrayList<>();
        for(int f=0;f<forks;f++) {
            int at=1+(int)(rand(seed,900+f)*(n-1));
            Vec3 root=spine[Math.min(n-1,at)];
            Vec3 heading=spine[Math.min(n,at+1)].subtract(root);
            if(heading.lengthSqr()<1e-8)continue;
            Vec3 bent=heading.normalize()
                .add(side.scale((rand(seed,910+f)-.5)*1.7))
                .add(up.scale((rand(seed,920+f)-.5)*1.7));
            if(bent.lengthSqr()<1e-8)continue;
            double reach=span*(.18+rand(seed,930+f)*.34);
            int steps=2+(int)(rand(seed,940+f)*3);
            Vec3[] fork=new Vec3[steps+1];
            fork[0]=root;
            for(int i=1;i<=steps;i++) {
                double t=i/(double)steps;
                fork[i]=root.add(bent.normalize().scale(reach*t))
                    .add(side.scale((rand(seed,950+f*8+i)-.5)*chaos*.8))
                    .add(up.scale((rand(seed,970+f*8+i)-.5)*chaos*.8));
            }
            branches.add(fork);
        }
        return new Arc(spine,branches);
    }

    /** Two passes: a wide dim sheath for presence, then a thin hot core so the arc still reads as thin. */
    public static void draw(BranchVfx.Painter painter,RenderType type,Arc arc,double width,float phase,float alpha) {
        trace(painter,type,arc.spine(),width*2.6,phase,.22f,alpha*.30f);
        trace(painter,type,arc.spine(),width,phase,.22f,alpha);
        for(Vec3[] fork:arc.forks()) {
            trace(painter,type,fork,width*1.7,phase+.14f,.3f,alpha*.20f);
            trace(painter,type,fork,width*.62,phase+.14f,.3f,alpha*.78f);
        }
    }
    public static void drawBranch(BranchVfx.Painter painter,RenderType type,Arc arc,double width,float phase,float alpha) {
        traceBranch(painter,type,arc.spine(),width*2.6,phase,.22f,alpha*.24f);
        traceBranch(painter,type,arc.spine(),width,phase,.22f,alpha);
        for(Vec3[] fork:arc.forks()) {
            traceBranch(painter,type,fork,width*1.7,phase+.14f,.3f,alpha*.18f);
            traceBranch(painter,type,fork,width*.62,phase+.14f,.3f,alpha*.78f);
        }
    }
    private static void trace(BranchVfx.Painter painter,RenderType type,Vec3[] points,double width,float phase,float spread,float alpha) {
        for(int i=0;i<points.length-1;i++) {
            float t=i/(float)Math.max(1,points.length-1);
            // Brightest where the branches cross, which is the point of them.
            float heat=Mth.clamp(1-Math.abs(t-.5f)*1.6f,0,1);
            BranchVfx.ribbon(painter,type,points[i],points[i+1],width,
                TemporalPalette.hot(phase+t*spread,heat*.55f),alpha);
        }
    }

    private static void traceBranch(BranchVfx.Painter painter,RenderType type,Vec3[] points,double width,float phase,float spread,float alpha) {
        for(int i=0;i<points.length-1;i++) {
            float t=i/(float)Math.max(1,points.length-1);
            float heat=Mth.clamp(1-Math.abs(t-.5f)*1.6f,0,1);
            BranchVfx.ribbon(painter,type,points[i],points[i+1],width,
                TimeBranchPalette.hot(phase+t*spread,heat*.72f),alpha);
        }
    }

    private static long mix(long x) {
        x^=x>>>33;x*=0xff51afd7ed558ccdL;
        x^=x>>>33;x*=0xc4ceb9fe1a85ec53L;
        return x^x>>>33;
    }
    /** Deterministic 0..1 from a seed and an index; no allocation and no shared random state. */
    public static double rand(long seed,int index) {
        return (mix(seed*6364136223846793005L+index)>>>11)/(double)(1L<<53);
    }
}
