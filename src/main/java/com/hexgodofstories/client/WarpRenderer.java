package com.hexgodofstories.client;

import com.hexgodofstories.warping.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import java.util.*;

/** Depth-tested stencil windows: the destination is spatial geometry seen through a broken floor. */
public final class WarpRenderer {
    private static final Map<Integer,CompoundTag> WINDOWS=new HashMap<>();
    private static final Map<Integer,Break> BREAKS=new HashMap<>();
    private static CompoundTag realm=new CompoundTag();
    public static void receive(int id,CompoundTag n){if(n.getBoolean("clear")){WINDOWS.remove(id);BREAKS.remove(id);}else WINDOWS.put(id,n);}
    public static String chargeLabel(int id){
        CompoundTag n=WINDOWS.get(id);if(n==null)return "";
        boolean recall=n.getBoolean("recall");
        if(n.getLong("opened")>=0){
            // The window is sent rather than assumed: a recall stays open for its own count, which
            // is not the ten seconds a crossing gets.
            int window=n.contains("window")?n.getInt("window"):WarpMath.OPEN_TICKS;
            return (recall?"REACHING IN  /  ":"REALITY OPEN  /  ")
                +Math.max(0,(window-(ClientState.now()-n.getLong("opened"))+19)/20)+"s";
        }
        int ticks=(int)(ClientState.now()-n.getLong("start"));
        if(recall)return "Reaching into "+Destination.at(n.getInt("destination")).title+"...";
        return String.format(java.util.Locale.ROOT,"%s  %d%%  /  %.1f blocks  /  %d energy",
            ticks<WarpMath.MIN_CHARGE?"Forming":"Release to trap",Math.min(100,ticks),WarpMath.width(ticks),
            Math.round(com.hexgodofstories.data.Ability.WARPING.cost*WarpMath.costScale(Math.min(ticks,WarpMath.FULL_CHARGE))));
    }
    public static void realm(CompoundTag n){realm=n;}
    public static void clear(){WINDOWS.clear();BREAKS.clear();realm=new CompoundTag();WarpScene.clear();}
    public static void render(RenderLevelStageEvent e){
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var pose=e.getPoseStack();Vec3 camera=e.getCamera().getPosition();double time=mc.level.getGameTime()+e.getPartialTick();
        WINDOWS.values().removeIf(n->!n.getString("dimension").equals(mc.level.dimension().location().toString())||n.getLong("until")<=mc.level.getGameTime());
        BREAKS.keySet().removeIf(id->!WINDOWS.containsKey(id));
        if(WINDOWS.isEmpty())return;
        // Forge exposes a depth-stencil target. No alternate world load, invasive renderer replacement or recursion.
        if(!mc.getMainRenderTarget().isStencilEnabled())mc.getMainRenderTarget().enableStencil();
        for(Map.Entry<Integer,CompoundTag> entry:WINDOWS.entrySet())window(pose,camera,time,entry.getKey(),entry.getValue());
    }
    /** Render spatial realm geometry before particles, with explicit depth state independent of effects. */
    public static void renderRealm(RenderLevelStageEvent e){
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var pose=e.getPoseStack();Vec3 camera=e.getCamera().getPosition();double time=mc.level.getGameTime()+e.getPartialTick();
        Destination d=Destination.from(mc.level);
        if(d!=null){
            pose.pushPose();pose.translate((d==Destination.GRAVITY_WELL||d==Destination.CRUSHING_REALM?0:WarpMath.cellX(camera.x))-camera.x,-camera.y,-camera.z);
            RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.depthMask(true);
            RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableCull();RenderSystem.setShaderColor(1,1,1,1);
            try{WarpScene.draw(pose,d,time,realm.contains("time")?realm.getLong("age")+(long)time-realm.getLong("time"):0,false);}finally{pose.popPose();RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.setShaderColor(1,1,1,1);}
        }
    }
    private static void window(PoseStack pose,Vec3 camera,double time,int id,CompoundTag n){
        Vec3 at=new Vec3(n.getDouble("x"),n.getDouble("y"),n.getDouble("z"));
        if(camera.distanceToSqr(at)>96*96||camera.y<at.y)return;
        Destination d=Destination.at(n.getInt("destination"));boolean open=n.getLong("opened")>=0;
        int held=open?n.getInt("held"):(int)(time-n.getLong("start"));
        double age=open?Math.max(0,time-n.getLong("opened")):0;
        Break brk=shape(id,n,at,held,open,(long)time);
        if(brk==null||brk.count==0)return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableCull();
        GL11.glEnable(GL11.GL_STENCIL_TEST);GL11.glStencilMask(255);GL11.glClearStencil(0);GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glStencilFunc(GL11.GL_ALWAYS,1,255);GL11.glStencilOp(GL11.GL_KEEP,GL11.GL_KEEP,GL11.GL_REPLACE);
        pose.pushPose();pose.translate(at.x-camera.x,at.y-camera.y,at.z-camera.z);
        try{
            RenderSystem.colorMask(false,false,false,false);RenderSystem.depthMask(false);RenderSystem.enableDepthTest();
            aperture(pose.last().pose(),brk);
            GL11.glStencilMask(0);GL11.glStencilFunc(GL11.GL_EQUAL,1,255);GL11.glStencilOp(GL11.GL_KEEP,GL11.GL_KEEP,GL11.GL_KEEP);
            // Draw the Nothingness backing from the EXACT aperture polygons, including fractional edges.
            RenderSystem.colorMask(true,true,true,true);aperture(pose.last().pose(),brk);
            RenderSystem.colorMask(false,false,false,false);
            // Reset depth only inside the visible opening. The surrounding terrain and creatures remain occluders.
            RenderSystem.depthMask(true);GL11.glDepthFunc(GL11.GL_ALWAYS);GL11.glDepthRange(1,1);aperture(pose.last().pose(),brk);GL11.glDepthRange(0,1);GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            pose.pushPose();pose.translate(-d.arrival.x,-d.arrival.y,-d.arrival.z);
            try{
                WarpScene.sky(pose,d,time);
                WarpScene.draw(pose,d,time,open?n.getLong("realmAge")+(long)Math.max(0,time-n.getLong("sent")):0,true);
            }finally{pose.popPose();}
            RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            // Restore the floor depth after drawing the remote scene, so it cannot occlude unrelated world effects.
            RenderSystem.colorMask(false,false,false,false);GL11.glDepthFunc(GL11.GL_ALWAYS);aperture(pose.last().pose(),brk);GL11.glDepthFunc(GL11.GL_LEQUAL);RenderSystem.colorMask(true,true,true,true);
            GL11.glDisable(GL11.GL_STENCIL_TEST);RenderSystem.depthMask(false);
            mirror(pose.last().pose(),brk,at,open,age,held,time,d.color);
        }finally{
            pose.popPose();GL11.glStencilMask(255);GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glDepthRange(0,1);GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.setShaderColor(1,1,1,1);
        }
    }

    // ------------------------------------------------------------------ the break, lying on the ground

    /**
     * One portal's fracture, resolved onto the blocks it is lying across.
     *
     * <p>Rebuilt when the charge moves it on a tick, and re-sampled against the world a few times a
     * second so a floor that changes underneath an open portal is followed rather than ignored.
     */
    private static final class Break {
        final long seed;final int held;final boolean open;final long sampled;
        int count;double[] v;int[] kind,rim;
        /** Surface height per block column, kept between rebuilds: the glass grows, the floor does not. */
        Map<Long,Double> columns;
        /**
         * The colour of the floor, per block column, kept alongside its height.
         *
         * <p>This is what makes the shards that come loose look like pieces of <em>this</em> place.
         * A break over turf throws up green and brown, one over a beach throws up sand, one over
         * stone throws up grey, and a break spanning the line between two of them throws up both.
         * The map colour is the right source for it: every block in the game has one, including
         * every modded block, so nothing has to be enumerated and nothing is ever the wrong colour
         * by default.
         */
        Map<Long,Integer> tints;
        Break(long seed,int held,boolean open,long sampled){this.seed=seed;this.held=held;this.open=open;this.sampled=sampled;}
        Vec3 corner(int piece,int index){int o=piece*12+index*3;return new Vec3(v[o],v[o+1],v[o+2]);}
        Vec3 centre(int piece){
            Vec3 sum=Vec3.ZERO;
            for(int i=0;i<4;i++)sum=sum.add(corner(piece,i));
            return sum.scale(.25);
        }
        int tint(double lx,double lz,Vec3 at){
            Integer known=tints.get(key(at.x+lx,at.z+lz));
            return known==null?0x7a7a7a:known;
        }
    }
    private static Break shape(int id,CompoundTag n,Vec3 at,int held,boolean open,long now){
        long seed=n.getLong("seed");
        Break cached=BREAKS.get(id);
        // The pattern is rebuilt whenever the charge has moved it on; the floor under it is asked
        // again a couple of times a second, which is often enough to follow a floor being changed.
        boolean fresh=cached!=null&&cached.seed==seed&&now-cached.sampled<40;
        if(fresh&&cached.held==held&&cached.open==open)return cached;
        ClientLevel level=Minecraft.getInstance().level;if(level==null)return cached;
        Break brk=new Break(seed,held,open,fresh?cached.sampled:now);
        List<WarpFracture.Piece> pieces=WarpFracture.build(seed,WarpMath.reach(held),open?1:WarpMath.charge(held));
        brk.v=new double[pieces.size()*12];brk.kind=new int[pieces.size()];brk.rim=new int[pieces.size()];
        Map<Long,Double> columns=fresh?cached.columns:new HashMap<>();
        Map<Long,Integer> tints=fresh?cached.tints:new HashMap<>();
        brk.columns=columns;brk.tints=tints;
        for(WarpFracture.Piece piece:pieces){
            int o=brk.count*12;boolean whole=true;
            for(int i=0;i<4;i++){
                double lx=piece.x[i],lz=piece.z[i];
                double y=column(level,columns,at.x+lx,at.z+lz,at.y,Math.sqrt(lx*lx+lz*lz));
                if(Double.isNaN(y)){whole=false;break;}
                tint(level,tints,at.x+lx,at.z+lz,y);
                brk.v[o+i*3]=lx;brk.v[o+i*3+1]=y-at.y+.025;brk.v[o+i*3+2]=lz;
            }
            // A piece whose floor is missing — a cliff face, a hole, a wall — is simply not part of
            // this break. Reality cracks across what is there to crack.
            if(!whole)continue;
            brk.kind[brk.count]=piece.kind;brk.rim[brk.count]=piece.rim;brk.count++;
        }
        BREAKS.put(id,brk);
        return brk;
    }
    /** Surface height for one block column, cached so a piece spanning a step still shares its corners. */
    private static double column(ClientLevel level,Map<Long,Double> cache,double wx,double wz,double originY,double distance){
        long key=key(wx,wz);
        Double known=cache.get(key);
        if(known!=null)return known;
        double y=WarpSurface.height(level,wx,wz,originY,distance);
        cache.put(key,y);
        return y;
    }
    /** What the floor of one block column is coloured, sampled from the block the surface belongs to. */
    private static void tint(ClientLevel level,Map<Long,Integer> cache,double wx,double wz,double surface){
        long key=key(wx,wz);
        if(cache.containsKey(key))return;
        net.minecraft.core.BlockPos pos=new net.minecraft.core.BlockPos(Mth.floor(wx),Mth.floor(surface-.05),Mth.floor(wz));
        cache.put(key,level.getBlockState(pos).getMapColor(level,pos).col);
    }
    private static long key(double wx,double wz){return ((long)Mth.floor(wx)<<32)^(Mth.floor(wz)&0xFFFFFFFFL);}

    // ------------------------------------------------------------------ drawing

    private static void aperture(Matrix4f m,Break brk){
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        for(int i=0;i<brk.count;i++)WarpMesh.quad(b,m,brk.corner(i,0),brk.corner(i,1),brk.corner(i,2),brk.corner(i,3),0x000000,1);
        BufferUploader.drawWithShader(b.end());
    }

    /**
     * Pieces of the world coming loose.
     *
     * <p>A crack drawn on the ground is a decal. What sells a pane of reality breaking is the
     * debris: slivers of the actual floor tipping up out of their own plane, turning over, sliding
     * inward and going into the hole. So every piece of the fracture that has been born yet sheds
     * one, anchored where that piece lies, cut from the colour of the block that is genuinely
     * underneath it — turf over a meadow, grey over stone, pale over sand, and both at once where a
     * break spans the line between two of them.
     *
     * <p>The motion is one arc, staggered across the break so it is a continuous shedding rather
     * than a pulse. A shard trembles where it sits while it is still nearly flat, tips over as it
     * rises, is drawn toward the middle the whole time, and thins to nothing as it reaches the
     * opening. How many are loose at once follows the charge, which is what makes the surface look
     * intact at the first crack, visibly coming apart by the time sections are separating, and
     * finally spent a second or so after the way through is open.
     */
    private static void shards(BufferBuilder b,Matrix4f m,Break brk,Vec3 at,boolean open,double age,int held,double time){
        double charge=open?1:WarpMath.charge(held);
        // Nothing comes off a hairline, and the shedding stops shortly after the break is open:
        // by then there is a portal rather than a surface still failing.
        double shed=open?Math.max(0,1-age/36.0):Math.max(0,charge-.06)/.94;
        if(shed<=.001)return;
        int loose=(int)Math.ceil(brk.count*Math.min(1,shed*1.2));
        for(int i=0;i<brk.count&&i<loose;i++){
            // The impact hole is the part that is simply gone rather than the part that is failing,
            // so most of it is skipped; the cracks and the slivers between them do the shedding.
            if(brk.kind[i]==WarpFracture.CORE&&i%4!=0)continue;
            Vec3 anchor=brk.centre(i);
            double spin=noise(brk.seed,i,1)<.5?-1:1;
            double p=(time*.034+noise(brk.seed,i,2))%1.0;
            double size=(.10+noise(brk.seed,i,3)*.17)*(.55+.45*charge);
            // Up and over in one arc, and inward for the whole of it.
            double rise=Math.sin(p*Math.PI*.88)*(.18+.62*charge);
            double drawn=p*(.16+.42*charge);
            double tremble=(1-Math.min(1,p*3.2))*.022*Math.sin(time*1.7+i*2.1);
            Vec3 centre=new Vec3(anchor.x*(1-drawn)+tremble,anchor.y+rise+.03,anchor.z*(1-drawn)-tremble);
            double tilt=Math.min(1.45,p*2.3),yaw=noise(brk.seed,i,4)*6.28+p*5.2*spin;
            Vec3 n=new Vec3(Math.sin(tilt)*Math.cos(yaw),Math.cos(tilt),Math.sin(tilt)*Math.sin(yaw));
            Vec3 u=n.cross(new Vec3(0,1,.001)).normalize(),v=n.cross(u);
            double sx=size*(1-p*.35),sz=size*(.6+noise(brk.seed,i,5)*.5)*(1-p*.35);
            // Four uneven corners: a shard, not a tile.
            Vec3 c0=centre.add(u.scale(sx)).add(v.scale(sz*.55));
            Vec3 c1=centre.add(u.scale(-sx*.72)).add(v.scale(sz));
            Vec3 c2=centre.add(u.scale(-sx)).add(v.scale(-sz*.48));
            Vec3 c3=centre.add(u.scale(sx*.5)).add(v.scale(-sz));
            float alpha=(float)(Math.min(1,p*7)*Math.pow(1-p,1.35)*(.42+.58*charge));
            if(alpha<=.004)continue;
            // Sampled at a corner rather than at the middle. Only the corners' columns were asked
            // about when the break was resolved onto the floor, so a wide piece whose centre falls
            // in a column nobody sampled would have come back grey.
            Vec3 sample=brk.corner(i,0);
            int ground=brk.tint(sample.x,sample.z,at);
            WarpMesh.quad(b,m,c0,c1,c2,c3,WarpMesh.shade(ground,.62+noise(brk.seed,i,6)*.55),alpha);
            // The lit edge. A fragment of ground that catches the light the break is throwing is
            // what stops the debris reading as dirt and starts it reading as glass.
            WarpMesh.ribbon(b,m,c0,c1,size*.055,0xf2fbff,alpha*.8f);
        }
    }

    /** A stable number in nought-to-one for one shard and one of its properties. */
    private static double noise(long seed,int index,int slot){
        long h=seed*0x9E3779B97F4A7C15L+index*0x632BE59BD9B4E019L+slot*0x4F1BBCDDL;
        h^=h>>>29;h*=0x94D049BB133111EBL;h^=h>>>32;
        return (h>>>11)/(double)(1L<<53);
    }

    /**
     * Light along every torn edge, glass over what is left, and slivers thrown up when it opens.
     *
     * <p>All of it is hung off the fracture's own pieces rather than off a ring, so the glow traces
     * the silhouette the break actually has: long on the side a crack ran, absent where it did not,
     * and tapering to nothing at every point.
     */
    private static void mirror(Matrix4f m,Break brk,Vec3 at,boolean open,double age,int held,double time,int color){
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        double burst=open?Math.min(1,age/22.0):0;
        double closing=open?Math.max(0,(age-(WarpMath.OPEN_TICKS-14))/14.0):0;
        for(int i=0;i<brk.count;i++){
            int kind=brk.kind[i],rim=brk.rim[i];
            Vec3 c0=brk.corner(i,0),c1=brk.corner(i,1),c2=brk.corner(i,2),c3=brk.corner(i,3);
            // Reflective-looking tinted glass over the wide pieces; the destination scene stays visible below.
            float glass=(float)(open?Math.max((1-burst)*.42,closing*.5):.17+.1*Math.sin(i*2.1+time*.025));
            if(kind!=WarpFracture.CRACK&&glass>.001)WarpMesh.quad(b,m,c0,c1,c2,c3,i%3==0?0xd5f1fa:0x728aab,glass);
            float pulse=(float)(.65+.18*Math.sin(time*.09+i*.71));
            for(int edge=0;edge<4;edge++){
                if((rim&1<<edge)==0)continue;
                Vec3 p=brk.corner(i,edge).add(0,.035,0),q=brk.corner(i,(edge+1)%4).add(0,.035,0);
                if(p.distanceToSqr(q)<1.0E-6)continue;   // the pointed end of a crack has no edge to light
                // Layered fracture light follows straight, unequal edges, never a circular vortex.
                WarpMesh.ribbon(b,m,p,q,.085,color,.16f);
                WarpMesh.ribbon(b,m,p,q,.025,color,pulse);
                WarpMesh.ribbon(b,m,p,q,.007,0xf0faff,.94f);
                // Triangular slivers peel away in a staggered burst, and a few hover at the edge after.
                double t=open?Math.max(0,Math.min(1,(age-i%5)/24.0)):0;
                boolean launching=open&&age<29;
                if(launching||(i+edge)%7==0){
                    double lift=launching?Math.sin(t*Math.PI)*(.55+(i%4)*.24):.10+.06*Math.sin(time*.045+i);
                    Vec3 a=p.add(0,.025+lift,0),end=q.add(0,.025+lift*.82,0);
                    Vec3 point=p.lerp(q,.5).add(0,.14+lift*1.35,0);
                    float alpha=launching?(float)((1-t)*.85):.32f;
                    WarpMesh.quad(b,m,a,end,point,point,i%2==0?0xe0f5ff:0x92a9c7,alpha);
                    WarpMesh.ribbon(b,m,a,point,.007,0xffffff,alpha);
                }
            }
        }
        shards(b,m,brk,at,open,age,held,time);
        // An angular light front travels outward along the fractures as the mirror breaks open.
        if(open&&age<16){
            double sweep=.15+.85*age/16;
            for(int i=0;i<brk.count;i++){
                if(brk.kind[i]!=WarpFracture.CRACK)continue;
                // Swept across the floor, not through it: the height each piece sits at is the
                // ground's, and scaling that with the front would sink it into the terrain.
                Vec3 p=brk.corner(i,0),q=brk.corner(i,1);
                WarpMesh.ribbon(b,m,new Vec3(p.x*sweep,p.y+.06,p.z*sweep),new Vec3(q.x*sweep,q.y+.06,q.z*sweep),.025,color,(float)(1-age/16));
            }
        }
        BufferUploader.drawWithShader(b.end());
    }
}
