package com.loki.client;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The cloak is solved in world space, not in the player's model space. Row zero is written directly to the
 * shoulder line every tick, so the cape is attached by construction and cannot drift off the back; every other
 * row is a Verlet particle that simply lags behind. Trailing while running, lift on a fall, the sideways throw
 * of a sharp turn and settling on landing all fall out of that lag rather than being faked with a wave function.
 * Nothing here is networked: it is cosmetic motion derived from movement each client can already see.
 */
public final class TemporalCloth {
    public static final int ROWS=16,COLS=9;
    private static final double LENGTH=1.24,HALF_TOP=.29,HALF_BOTTOM=.47;
    private static final double GRAVITY=.021,DAMPING=.94,SEGMENT=LENGTH/(ROWS-1);
    private static final int PASSES=6;
    private static final double BODY_RADIUS=.34,TELEPORT=2.5;

    private final double[] x=new double[ROWS*COLS],y=new double[ROWS*COLS],z=new double[ROWS*COLS];
    private final double[] px=new double[ROWS*COLS],py=new double[ROWS*COLS],pz=new double[ROWS*COLS];
    private final double[] ox=new double[ROWS*COLS],oy=new double[ROWS*COLS],oz=new double[ROWS*COLS];
    private boolean ready;
    private int tick=Integer.MIN_VALUE;
    private Vec3 anchorCentre=Vec3.ZERO;
    private double ground=Double.NEGATIVE_INFINITY;

    private static int at(int row,int col) {return row*COLS+col;}
    public static double halfWidth(double t) {return HALF_TOP+(HALF_BOTTOM-HALF_TOP)*t;}
    private static double rowT(int row) {return row/(double)(ROWS-1);}
    private static double colU(int col) {return col/(double)(COLS-1)*2-1;}

    /** Advances the solve once per game tick. Safe to call every frame. */
    public void tick(LivingEntity p) {
        if(tick==p.tickCount)return;
        tick=p.tickCount;
        float yaw=p.yBodyRot;
        double rad=Math.toRadians(yaw);
        Vec3 sideways=new Vec3(Math.cos(rad),0,Math.sin(rad));
        Vec3 back=new Vec3(Math.sin(rad),0,-Math.cos(rad));
        boolean prone=p.isFallFlying()||p.getPose()==Pose.SWIMMING||p.isVisuallySwimming();
        double shoulder=prone?.42:p.getEyeHeight()-.17;
        Vec3 lean=p.isCrouching()?back.scale(-.15):Vec3.ZERO;
        Vec3 centre=p.position().add(0,shoulder,0).add(back.scale(prone?.05:.15)).add(lean);

        if(!ready) {
            reset(centre,sideways,back);
        } else if(centre.distanceToSqr(anchorCentre)>TELEPORT*TELEPORT) {
            // Blinking, slipping or changing dimension: carry the cloth along instead of letting it snap taut.
            Vec3 jump=centre.subtract(anchorCentre);
            for(int i=0;i<x.length;i++) {
                x[i]+=jump.x;y[i]+=jump.y;z[i]+=jump.z;
                px[i]+=jump.x;py[i]+=jump.y;pz[i]+=jump.z;
            }
        }
        anchorCentre=centre;
        System.arraycopy(x,0,ox,0,x.length);System.arraycopy(y,0,oy,0,y.length);System.arraycopy(z,0,oz,0,z.length);

        BlockHitResult hit=p.level().clip(new ClipContext(p.position().add(0,.1,0),p.position().add(0,-3.5,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        ground=hit.getType()==HitResult.Type.MISS?Double.NEGATIVE_INFINITY:hit.getLocation().y+.02;

        Vec3 motion=p.position().subtract(new Vec3(p.xo,p.yo,p.zo));
        double speed=motion.horizontalDistance();
        // A little outward push so the hem never sucks flat onto the legs, plus a slow idle breath.
        double billow=.006+speed*.05;
        double breath=Math.sin(p.tickCount*.06)*.0016;
        for(int row=1;row<ROWS;row++) {
            double t=rowT(row),loose=.3+.7*t;
            for(int col=0;col<COLS;col++) {
                int i=at(row,col);
                double vx=(x[i]-px[i])*DAMPING,vy=(y[i]-py[i])*DAMPING,vz=(z[i]-pz[i])*DAMPING;
                double turbulence=Math.sin(p.tickCount*.11+col*1.7+row*.5)*.0011*loose;
                Vec3 push=back.scale(billow*loose).add(sideways.scale(turbulence+breath*colU(col)));
                px[i]=x[i];py[i]=y[i];pz[i]=z[i];
                x[i]+=vx+push.x;
                y[i]+=vy-GRAVITY*loose;
                z[i]+=vz+push.z;
            }
        }
        for(int col=0;col<COLS;col++)writeAnchor(col,centre,sideways,back);
        for(int pass=0;pass<PASSES;pass++) {
            for(int row=1;row<ROWS;row++) {
                for(int col=0;col<COLS;col++) {
                    double t=rowT(row);
                    double restRow=Math.hypot(SEGMENT,halfWidth(t)-halfWidth(rowT(row-1)))*.99;
                    link(at(row-1,col),at(row,col),restRow,row==1?0:.35);
                    if(col+1<COLS)link(at(row,col),at(row,col+1),2*halfWidth(t)/(COLS-1),.5);
                }
            }
            for(int row=1;row<ROWS;row++)for(int col=0;col<COLS;col++)collide(at(row,col),p);
        }
        ready=true;
    }

    private void writeAnchor(int col,Vec3 centre,Vec3 sideways,Vec3 back) {
        double u=colU(col);
        Vec3 a=centre.add(sideways.scale(u*HALF_TOP)).add(back.scale(.02*u*u));
        int i=at(0,col);
        px[i]=x[i];py[i]=y[i];pz[i]=z[i];
        x[i]=a.x;y[i]=a.y;z[i]=a.z;
    }

    private void reset(Vec3 centre,Vec3 sideways,Vec3 back) {
        for(int row=0;row<ROWS;row++) {
            double t=rowT(row);
            for(int col=0;col<COLS;col++) {
                Vec3 v=centre.add(sideways.scale(colU(col)*halfWidth(t))).add(back.scale(.02+.14*t)).add(0,-LENGTH*t,0);
                int i=at(row,col);
                x[i]=px[i]=ox[i]=v.x;y[i]=py[i]=oy[i]=v.y;z[i]=pz[i]=oz[i]=v.z;
            }
        }
        ready=true;
    }

    private void link(int a,int b,double rest,double shareA) {
        double dx=x[b]-x[a],dy=y[b]-y[a],dz=z[b]-z[a];
        double d=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(d<1e-7)return;
        double k=(d-rest)/d,shareB=1-shareA;
        x[a]+=dx*k*shareA;y[a]+=dy*k*shareA;z[a]+=dz*k*shareA;
        x[b]-=dx*k*shareB;y[b]-=dy*k*shareB;z[b]-=dz*k*shareB;
    }

    /** Keeps the cloth outside the wearer and above the floor so it never saws through either. */
    private void collide(int i,LivingEntity p) {
        if(y[i]<ground){y[i]=ground;px[i]+=(x[i]-px[i])*.5;pz[i]+=(z[i]-pz[i])*.5;}
        double top=p.getY()+p.getBbHeight();
        if(y[i]>top||y[i]<p.getY()-.1)return;
        double dx=x[i]-p.getX(),dz=z[i]-p.getZ();
        double flat=Math.sqrt(dx*dx+dz*dz);
        double radius=y[i]>p.getY()+p.getBbHeight()*.45?BODY_RADIUS:BODY_RADIUS*.8;
        if(flat>=radius)return;
        if(flat<1e-6){x[i]+=radius;return;}
        double scale=radius/flat;
        x[i]=p.getX()+dx*scale;
        z[i]=p.getZ()+dz*scale;
    }

    /** Interpolated world position of one grid node. */
    public Vec3 node(int row,int col,float partial) {
        int i=at(Mth.clamp(row,0,ROWS-1),Mth.clamp(col,0,COLS-1));
        return new Vec3(Mth.lerp(partial,ox[i],x[i]),Mth.lerp(partial,oy[i],y[i]),Mth.lerp(partial,oz[i],z[i]));
    }
    public boolean ready() {return ready;}
}
