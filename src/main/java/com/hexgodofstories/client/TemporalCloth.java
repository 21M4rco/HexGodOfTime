package com.hexgodofstories.client;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * World-space cloth with attachment and body collision taken from the rendered torso bone.
 *
 * <p>Two invariants keep the simulation from ever leaving the wearer, which is what a Verlet chain
 * otherwise does the moment something feeds it a bad number — a teleport, a shoulder bone an animation
 * mod has scaled to nothing, or a particle wedged inside terrain. Both are enforced after every solver
 * pass and again before anything is handed to the renderer:
 *
 * <ul>
 *   <li>no node may sit further from the shoulder seam than the length of fabric above it, and</li>
 *   <li>no node may sit further from the node it hangs from than one slightly stretched segment.</li>
 * </ul>
 *
 * Together they make a cloak that reaches across the world geometrically impossible rather than merely
 * unlikely, so a single bad frame can no longer leave a permanent spike anchored to a floor. Anything
 * non-finite, and any transform that cannot be inverted, re-seeds the whole grid from the body instead
 * of being propagated.
 */
public final class TemporalCloth {
    public static final int ROWS=22,COLS=13;
    private static final double LENGTH=2.22,HALF_TOP=.29,HALF_BOTTOM=1.34;
    private static final double SEGMENT=LENGTH/(ROWS-1),GRAVITY=.022,DAMPING=.92;
    private static final int PASSES=10;
    /** A segment may stretch by this much before the safety clamp pulls it straight back. */
    private static final double STRETCH=1.35;
    /** Largest distance a single node may travel in one simulation tick. */
    private static final double MAX_STEP=.9;
    /** Re-seed rather than chase the body when the seam jumps this far between ticks. */
    private static final double TELEPORT=2.5;

    private final Vec3[] current=new Vec3[ROWS*COLS],previous=new Vec3[ROWS*COLS],old=new Vec3[ROWS*COLS];
    private final double[] floors=new double[ROWS*COLS];
    private boolean ready;
    private long tick=Long.MIN_VALUE;
    private Vec3 centre=Vec3.ZERO,solvedCentre=Vec3.ZERO;
    private BodyFrame frame;

    /** Matrices use camera-relative coordinates, retaining precision far from world origin. */
    public static final class BodyFrame {
        private final Matrix4f toWorld,toBody;
        private final Vec3 camera;
        private final boolean usable;

        public BodyFrame(Matrix4f matrix,Vec3 camera) {
            toWorld=new Matrix4f(matrix);
            toBody=new Matrix4f(matrix);
            this.camera=camera;
            boolean sound=invertible(toWorld)&&finite(camera);
            if(sound)toBody.invert();
            usable=sound&&finite(toBody);
        }

        /**
         * A torso bone can legitimately be rotated any way at all, but a transform that has been scaled
         * flat — which animation mods do to hide a part — inverts to infinities. Reject those here, once,
         * rather than letting them reach the solver as coordinates.
         */
        private static boolean invertible(Matrix4f m) {
            if(!finite(m))return false;
            for(int axis=0;axis<3;axis++) {
                double x=m.get(axis,0),y=m.get(axis,1),z=m.get(axis,2);
                double length=Math.sqrt(x*x+y*y+z*z);
                if(!(length>.02&&length<8))return false;
            }
            return Math.abs(m.determinant())>1e-5;
        }
        private static boolean finite(Matrix4f m) {
            for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(!Float.isFinite(m.get(c,r)))return false;
            return true;
        }
        private static boolean finite(Vec3 v) {return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}

        public boolean usable() {return usable;}

        private Vec3 world(double x,double y,double z) {
            Vector3f v=toWorld.transformPosition(new Vector3f((float)x,(float)y,(float)z));
            return camera.add(v.x,v.y,v.z);
        }
        public Vec3 anchor(int col) {
            double x=(col/(double)(COLS-1)*2-1)*HALF_TOP;
            // Follow the actual rear arc of collar.obj at t=.5, with a small overlap under the mantle.
            double rear=Math.sqrt(1-x*x/(.381*.381));
            return world(x,.055+.035*rear,.2125*rear);
        }
        public Vec3 back() {return safeDirection(world(0,0,1).subtract(world(0,0,0)),new Vec3(0,0,1));}
        public Vec3 side() {return safeDirection(world(1,0,0).subtract(world(0,0,0)),new Vec3(1,0,0));}
        private static Vec3 safeDirection(Vec3 v,Vec3 fallback) {
            return finite(v)&&v.lengthSqr()>1e-8?v.normalize():fallback;
        }
        public Vec3 outsideBody(Vec3 point) {
            if(!usable||!finite(point))return point;
            Vec3 relative=point.subtract(camera);
            Vector3f local=toBody.transformPosition(new Vector3f((float)relative.x,(float)relative.y,(float)relative.z));
            if(!Float.isFinite(local.x)||!Float.isFinite(local.y)||!Float.isFinite(local.z))return point;
            // Keep the entire fabric on the back side, including animated arms and swinging legs.
            // A radial push can eject particles out the FRONT after a fast turn; this cannot.
            double width=local.y<.76?.57:.44;
            if(local.y>=-.52&&local.y<=1.56&&Math.abs(local.x)<width) {
                float back=local.y<0?.30f:local.y<.76f?.22f:.62f;
                if(local.z<back) {
                    Vec3 pushed=world(local.x,local.y,back);
                    return finite(pushed)?pushed:point;
                }
            }
            return point;
        }
    }

    private static int at(int row,int col){return row*COLS+col;}
    public static double halfWidth(double t){t=Mth.clamp(t,0,1);double flare=t*t*(3-2*t);return HALF_TOP+(HALF_BOTTOM-HALF_TOP)*flare;}
    private static boolean finite(Vec3 v) {return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}

    public void tick(LivingEntity wearer,BodyFrame body) {
        if(body==null||!body.usable())return;
        Vec3 seam=body.anchor(COLS/2);
        if(!finite(seam)||seam.distanceToSqr(wearer.position())>64)return;
        frame=body;centre=seam;
        long now=wearer.tickCount;
        if(!ready||centre.distanceToSqr(solvedCentre)>TELEPORT*TELEPORT||now<tick||now-tick>5)reset(body,wearer);
        // The pinned row is evaluated every frame, even if no simulation tick has elapsed.
        if(tick==now)return;
        tick=now;
        System.arraycopy(current,0,old,0,current.length);
        Vec3 back=body.back(),side=body.side();
        double speed=wearer.getDeltaMovement().horizontalDistance();
        if(!Double.isFinite(speed))speed=0;
        for(int row=1;row<ROWS;row++)for(int col=0;col<COLS;col++) {
            int i=at(row,col);double t=row/(double)(ROWS-1);
            Vec3 velocity=step(current[i].subtract(previous[i]).scale(DAMPING));
            previous[i]=current[i];
            current[i]=current[i].add(velocity).add(0,-GRAVITY,0)
                .add(back.scale(.003+Math.min(.025,speed*.04)*t))
                .add(side.scale(Math.sin(wearer.tickCount*.08+row*.4+col)*.0007*t));
            current[i]=sweep(wearer,previous[i],current[i]);
            // Each particle samples its own collision surface: steps, slabs and ledges aren't a flat plane.
            var hit=wearer.level().clip(new ClipContext(current[i].add(0,.3,0),current[i].add(0,-1.1,0),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,wearer));
            floors[i]=hit.getType()==HitResult.Type.MISS?Double.NEGATIVE_INFINITY:hit.getLocation().y+.025;
        }
        for(int col=0;col<COLS;col++)current[at(0,col)]=body.anchor(col);
        for(int pass=0;pass<PASSES;pass++) {
            for(int row=1;row<ROWS;row++)for(int col=0;col<COLS;col++) {
                double t=row/(double)(ROWS-1);
                double u=col/(double)(COLS-1)*2-1;
                double flare=u*(halfWidth(t)-halfWidth((row-1)/(double)(ROWS-1)));
                link(at(row-1,col),at(row,col),Math.hypot(SEGMENT,flare),row==1?0:.45);
                if(col+1<COLS)link(at(row,col),at(row,col+1),2*halfWidth(t)/(COLS-1),.5);
                if(col+1<COLS)link(at(row-1,col),at(row,col+1),Math.hypot(SEGMENT,2*halfWidth(t)/(COLS-1)+flare),row==1?0:.5);
            }
            for(int row=1;row<ROWS;row++)for(int col=0;col<COLS;col++) {
                int i=at(row,col);
                current[i]=contain(row,col,body.outsideBody(current[i]));
                if(current[i].y<floors[i]) {
                    current[i]=new Vec3(current[i].x,floors[i],current[i].z);
                    // Drag rather than weld: only the motion along the ground is damped, so the hem
                    // still trails the wearer away instead of staying pinned to the block it touched.
                    Vec3 rest=previous[i];
                    previous[i]=new Vec3(Mth.lerp(.35,rest.x,current[i].x),current[i].y,Mth.lerp(.35,rest.z,current[i].z));
                }
            }
        }
        for(int row=1;row<ROWS;row++)for(int col=0;col<COLS;col++) {
            int i=at(row,col);
            current[i]=contain(row,col,body.outsideBody(sweep(wearer,previous[i],current[i])));
        }
        if(!sane()){reset(body,wearer);return;}
        solvedCentre=centre;
    }

    /** Verlet inertia is the one place a single wild frame becomes permanent; cap it instead. */
    private static Vec3 step(Vec3 velocity) {
        if(!finite(velocity))return Vec3.ZERO;
        double length=velocity.length();
        return length>MAX_STEP?velocity.scale(MAX_STEP/length):velocity;
    }

    /**
     * The two hard invariants. A node stays within the fabric hanging above it and within one stretched
     * segment of its parent, so no arrangement of forces, collisions or transforms can draw the cloak out
     * into the world.
     */
    private Vec3 contain(int row,int col,Vec3 point) {
        if(!finite(point))return current[at(row-1,col)];
        Vec3 parent=current[at(row-1,col)];
        Vec3 fromParent=point.subtract(parent);
        double parentLength=fromParent.length();
        double parentLimit=Math.hypot(SEGMENT,2*HALF_BOTTOM/(COLS-1))*STRETCH;
        if(parentLength>parentLimit)point=parent.add(fromParent.scale(parentLimit/parentLength));
        Vec3 fromSeam=point.subtract(centre);
        double seamLength=fromSeam.length();
        double seamLimit=row*SEGMENT*STRETCH+HALF_BOTTOM+.1;
        if(seamLength>seamLimit)point=centre.add(fromSeam.scale(seamLimit/seamLength));
        return point;
    }

    private boolean sane() {
        for(Vec3 v:current)if(!finite(v))return false;
        return true;
    }

    private static Vec3 sweep(LivingEntity p,Vec3 from,Vec3 to) {
        if(!finite(from)||!finite(to))return finite(to)?to:from;
        if(from.distanceToSqr(to)<1e-9)return to;
        var hit=p.level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        return hit.getType()==HitResult.Type.MISS?to:hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(.025));
    }

    private void reset(BodyFrame body,LivingEntity wearer) {
        Vec3 back=body.back(),side=body.side();
        for(int row=0;row<ROWS;row++)for(int col=0;col<COLS;col++) {
            double t=row/(double)(ROWS-1),u=col/(double)(COLS-1)*2-1;
            double drop=wearer.onGround()?Math.min(LENGTH*t,Math.max(.1,centre.y-wearer.getY()-.03)):LENGTH*t;
            double train=Math.max(0,LENGTH*t-drop);
            Vec3 v=body.anchor(col).add(side.scale(u*(halfWidth(t)-HALF_TOP))).add(back.scale(.18*t+train)).add(0,-drop,0);
            if(!finite(v))v=centre.add(0,-LENGTH*t,0);
            int i=at(row,col);current[i]=previous[i]=old[i]=v;floors[i]=Double.NEGATIVE_INFINITY;
        }
        solvedCentre=centre;ready=true;tick=Long.MIN_VALUE;
    }

    private void link(int a,int b,double rest,double shareA) {
        Vec3 delta=current[b].subtract(current[a]);double length=delta.length();
        if(!Double.isFinite(length)||length<1e-7)return;
        Vec3 correction=delta.scale((length-rest)/length);
        current[a]=current[a].add(correction.scale(shareA));current[b]=current[b].subtract(correction.scale(1-shareA));
    }

    public Vec3 node(int row,int col,float partial) {
        if(frame==null||!frame.usable())return centre;
        if(row==0)return frame.anchor(col);
        int i=at(row,col);
        Vec3 v=old[i].lerp(current[i],Mth.clamp(partial,0,1));
        // Frame interpolation must not detach the seam or carry fabric through a newly rotated torso.
        Vec3 seamDrift=frame.anchor(col).subtract(old[at(0,col)].lerp(current[at(0,col)],partial));
        if(finite(seamDrift))v=v.add(seamDrift.scale(Math.max(0,1-row/5.0)));
        v=frame.outsideBody(v);
        v=new Vec3(v.x,Math.max(v.y,floors[i]),v.z);
        // The invariants hold for the solved grid; they must hold for the interpolated one the renderer
        // consumes as well, or a single stale frame reintroduces exactly the spike they exist to prevent.
        if(!finite(v))return centre;
        Vec3 fromSeam=v.subtract(centre);
        double length=fromSeam.length();
        double limit=row*SEGMENT*STRETCH+HALF_BOTTOM+.1;
        return length>limit?centre.add(fromSeam.scale(limit/length)):v;
    }
    public boolean ready(){return ready&&frame!=null&&frame.usable();}
}
