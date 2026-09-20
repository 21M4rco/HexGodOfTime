package com.loki.client;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** World-space cloth with attachment and body collision taken from the rendered torso bone. */
public final class TemporalCloth {
    public static final int ROWS=22,COLS=13;
    private static final double LENGTH=2.22,HALF_TOP=.29,HALF_BOTTOM=1.34;
    private static final double SEGMENT=LENGTH/(ROWS-1),GRAVITY=.022,DAMPING=.92;
    private static final int PASSES=10;
    private final Vec3[] current=new Vec3[ROWS*COLS],previous=new Vec3[ROWS*COLS],old=new Vec3[ROWS*COLS];
    private final double[] floors=new double[ROWS*COLS];
    private boolean ready;
    private int tick=Integer.MIN_VALUE;
    private Vec3 centre=Vec3.ZERO,solvedCentre=Vec3.ZERO;
    private BodyFrame frame;

    /** Matrices use camera-relative coordinates, retaining precision far from world origin. */
    public static final class BodyFrame {
        private final Matrix4f toWorld,toBody;
        private final Vec3 camera;
        public BodyFrame(Matrix4f matrix,Vec3 camera) {
            toWorld=new Matrix4f(matrix);toBody=new Matrix4f(matrix).invert();this.camera=camera;
        }
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
        public Vec3 back() {return world(0,0,1).subtract(world(0,0,0)).normalize();}
        public Vec3 side() {return world(1,0,0).subtract(world(0,0,0)).normalize();}
        public Vec3 outsideBody(Vec3 point) {
            Vec3 relative=point.subtract(camera);
            Vector3f local=toBody.transformPosition(new Vector3f((float)relative.x,(float)relative.y,(float)relative.z));
            // Keep the entire fabric on the back side, including animated arms and swinging legs.
            // A radial push can eject particles out the FRONT after a fast turn; this cannot.
            double width=local.y<.76?.57:.44;
            if(local.y>=-.52&&local.y<=1.56&&Math.abs(local.x)<width) {
                float back=local.y<0?.30f:local.y<.76?.22f:.62f;
                if(local.z<back)return world(local.x,local.y,back);
            }
            return point;
        }
    }

    private static int at(int row,int col){return row*COLS+col;}
    public static double halfWidth(double t){t=Mth.clamp(t,0,1);double flare=t*t*(3-2*t);return HALF_TOP+(HALF_BOTTOM-HALF_TOP)*flare;}

    public void tick(LivingEntity wearer,BodyFrame body) {
        frame=body;centre=body.anchor(COLS/2);
        if(!ready||centre.distanceToSqr(solvedCentre)>6.25||wearer.tickCount-tick>5)reset(body,wearer);
        // The pinned row is evaluated every frame, even if no simulation tick has elapsed.
        if(tick==wearer.tickCount)return;
        tick=wearer.tickCount;
        System.arraycopy(current,0,old,0,current.length);
        Vec3 back=body.back(),side=body.side();
        double speed=wearer.getDeltaMovement().horizontalDistance();
        for(int row=1;row<ROWS;row++)for(int col=0;col<COLS;col++) {
            int i=at(row,col);double t=row/(double)(ROWS-1);
            Vec3 velocity=current[i].subtract(previous[i]).scale(DAMPING);
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
            for(int i=COLS;i<current.length;i++) {
                current[i]=body.outsideBody(current[i]);
                if(current[i].y<floors[i]) {
                    current[i]=new Vec3(current[i].x,floors[i],current[i].z);
                    // Friction lets the extra length lie on and drag across the ground.
                    previous[i]=previous[i].lerp(current[i],.35);
                }
            }
        }
        for(int i=COLS;i<current.length;i++)current[i]=body.outsideBody(sweep(wearer,previous[i],current[i]));
        solvedCentre=centre;
    }

    private static Vec3 sweep(LivingEntity p,Vec3 from,Vec3 to) {
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
            int i=at(row,col);current[i]=previous[i]=old[i]=v;floors[i]=Double.NEGATIVE_INFINITY;
        }
        solvedCentre=centre;ready=true;tick=Integer.MIN_VALUE;
    }
    private void link(int a,int b,double rest,double shareA) {
        Vec3 delta=current[b].subtract(current[a]);double length=delta.length();
        if(length<1e-7)return;
        Vec3 correction=delta.scale((length-rest)/length);
        current[a]=current[a].add(correction.scale(shareA));current[b]=current[b].subtract(correction.scale(1-shareA));
    }
    public Vec3 node(int row,int col,float partial) {
        if(row==0)return frame.anchor(col);
        int i=at(row,col);
        Vec3 v=old[i].lerp(current[i],Mth.clamp(partial,0,1));
        // Frame interpolation must not detach the seam or carry fabric through a newly rotated torso.
        Vec3 seamDrift=frame.anchor(col).subtract(old[at(0,col)].lerp(current[at(0,col)],partial));
        v=v.add(seamDrift.scale(Math.max(0,1-row/5.0)));
        v=frame.outsideBody(v);
        return new Vec3(v.x,Math.max(v.y,floors[i]),v.z);
    }
    public boolean ready(){return ready;}
}
