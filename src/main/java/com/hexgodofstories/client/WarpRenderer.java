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

/**
 * Depth-tested stencil windows: the destination is spatial geometry seen through a pool of liquid
 * lying on the floor.
 *
 * <p>The portal used to be a shattered mirror and is now something poured. The machinery underneath
 * is unchanged — a stencil cut to the opening's own outline, the floor's depth reset inside it, the
 * destination drawn through it and the floor's depth put back — because that machinery is what
 * makes another world genuinely visible through a hole rather than pasted onto a disc. What changed
 * is the shape it is cut to and what is drawn on top: one continuous sheet conforming to the ground
 * it has run across, with rings travelling out through it and a bright meniscus at its rim.
 */
public final class WarpRenderer {
    private static final Map<Integer,CompoundTag> WINDOWS=new HashMap<>();
    private static final Map<Integer,Break> BREAKS=new HashMap<>();
    private static CompoundTag realm=new CompoundTag();
    /** About 1.2 seconds for the whole puddle to crawl back into its centre. */
    private static final int CLOSE_TICKS=24;

    public static void receive(int id,CompoundTag n){
        if(!n.getBoolean("clear")){WINDOWS.put(id,n);return;}
        CompoundTag live=WINDOWS.get(id);
        if(live==null){BREAKS.remove(id);WarpShadows.forget(id);return;}
        beginClosing(id,live,ClientState.now());
    }

    private static void beginClosing(int id,CompoundTag live,long now){
        if(live.getBoolean("closing"))return;
        CompoundTag close=live.copy();
        close.putBoolean("closing",true);
        close.putLong("closeStart",now);
        boolean open=close.getLong("opened")>=0;
        close.putInt("closeHeld",open?close.getInt("held"):(int)Math.max(0,now-close.getLong("start")));
        close.putLong("until",now+CLOSE_TICKS+2);
        WINDOWS.put(id,close);BREAKS.remove(id);WarpShadows.forget(id);
    }
    public static String chargeLabel(int id){
        CompoundTag n=WINDOWS.get(id);if(n==null)return "";
        boolean recall=n.getBoolean("recall");
        if(n.getLong("opened")>=0){
            // The window is sent rather than assumed: a recall stays open for its own count, which
            // is not the ten seconds a crossing gets.
            int window=n.contains("window")?n.getInt("window"):WarpMath.OPEN_TICKS;
            return (recall?"REACHING IN  /  ":"THE WAY IS OPEN  /  ")
                +Math.max(0,(window-(ClientState.now()-n.getLong("opened"))+19)/20)+"s";
        }
        int ticks=(int)(ClientState.now()-n.getLong("start"));
        if(recall)return "Reaching into "+Destination.at(n.getInt("destination")).title+"...";
        return String.format(java.util.Locale.ROOT,"%s  %d%%  /  %.1f blocks  /  %d energy",
            ticks<WarpMath.MIN_CHARGE?"Spreading":"Release to open",Math.min(100,ticks),WarpMath.width(ticks),
            Math.round(com.hexgodofstories.data.Ability.WARPING.cost*WarpMath.costScale(Math.min(ticks,WarpMath.FULL_CHARGE))));
    }
    public static void realm(CompoundTag n){realm=n;}
    public static void clear(){WINDOWS.clear();BREAKS.clear();realm=new CompoundTag();WarpScene.clear();}
    public static void render(RenderLevelStageEvent e){
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var pose=e.getPoseStack();Vec3 camera=e.getCamera().getPosition();double time=mc.level.getGameTime()+e.getPartialTick();
        long now=mc.level.getGameTime();
        for(var entry:new ArrayList<>(WINDOWS.entrySet())){
            CompoundTag n=entry.getValue();
            if(!n.getString("dimension").equals(mc.level.dimension().location().toString())){WINDOWS.remove(entry.getKey());continue;}
            if(n.getLong("until")>now)continue;
            if(n.getBoolean("closing"))WINDOWS.remove(entry.getKey());
            else beginClosing(entry.getKey(),n,now);
        }
        BREAKS.keySet().removeIf(id->!WINDOWS.containsKey(id));
        WarpShadows.retain(WINDOWS.keySet());
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
        // A little below the pool's own height rather than exactly at it: a camera sinking into
        // one reaches the surface on the tick it crosses, and culling on the nose would blink the
        // other world out for the frame before the crossing rather than showing it arriving.
        boolean arrival=n.getBoolean("arrival");
        if(camera.distanceToSqr(at)>96*96||camera.y<at.y-(arrival?3.0:.3))return;
        Destination d=Destination.at(n.getInt("destination"));boolean open=n.getLong("opened")>=0;
        boolean closing=n.getBoolean("closing");
        int held=closing?n.getInt("closeHeld"):(open?n.getInt("held"):(int)(time-n.getLong("start")));
        double closeScale=closing?closeScale(n,time):1.0;
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
            // This also clips bodies at the portal plane, and that is load-bearing rather than
            // incidental. Entities are drawn before this stage, so the backing — which sits at the
            // floor's own height, per column, following the pool's exact outline — is nearer
            // than anything that has sunk below it and further than anything still above it. The
            // depth test therefore paints out exactly the part of a body that has gone through and
            // leaves the rest standing. Removing the depth test here, or moving this to a stage
            // before entities are drawn, would put whole players back on top of a hole they are
            // halfway down.
            RenderSystem.colorMask(true,true,true,true);aperture(pose.last().pose(),brk);
            RenderSystem.colorMask(false,false,false,false);
            // Reset depth only inside the visible opening. The surrounding terrain and creatures remain occluders.
            RenderSystem.depthMask(true);GL11.glDepthFunc(GL11.GL_ALWAYS);GL11.glDepthRange(1,1);aperture(pose.last().pose(),brk);GL11.glDepthRange(0,1);GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            // Opaque entrance: no destination scene is drawn through the floor any more.
            // Stencil/depth remains only to clip submerged body geometry correctly.
            RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            // Restore the floor depth after drawing the remote scene, so it cannot occlude unrelated world effects.
            RenderSystem.colorMask(false,false,false,false);GL11.glDepthFunc(GL11.GL_ALWAYS);aperture(pose.last().pose(),brk);GL11.glDepthFunc(GL11.GL_LEQUAL);RenderSystem.colorMask(true,true,true,true);
            RenderSystem.depthMask(false);
            // Keep the visibility stencil active for the cosmetic film. The old path disabled the
            // stencil and then drew this pass with GL_ALWAYS, which made the black puddle an x-ray
            // overlay: walls, trunks, leaves and any other foreground block could not occlude it.
            // The stencil was written with the normal world depth test, so it contains only pixels
            // where the aperture is genuinely visible from the camera. Drawing the film through
            // that same mask preserves the submerged look without letting it show through blocks.
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            groundFilm(pose.last().pose(),brk,open);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            // The actual goo volume is depth-tested normally so its raised lip, walls and bulges
            // occupy honest 3D space and are hidden by foreground geometry.
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            int window=n.contains("window")?n.getInt("window"):WarpMath.OPEN_TICKS;
            surface(pose.last().pose(),brk,open,age,held,time,window,closeScale);
        }finally{
            pose.popPose();GL11.glStencilMask(255);GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glDepthRange(0,1);GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.setShaderColor(1,1,1,1);
        }
    }

    /** Stages that have already been complained about, so a broken one logs once rather than per frame. */
    private static final Set<String> COMPLAINED=new HashSet<>();

    /**
     * One piece of the far side, drawn so that its failing cannot blank the rest of the window.
     *
     * <p>The three pieces — the realm's sky, its architecture and whatever is moving in it — are
     * independent, and until now they were not: a throw in any of them skipped the two after it,
     * skipped putting the floor's depth back, and skipped the pool's own surface, so one broken
     * stage read as "the portal stopped showing the other side" with nothing in the log to say
     * which. Worse, it unwound through the whole render pass. Each stands on its own now, and says
     * so once if it falls over.
     */
    private static void stage(String name,Runnable draw){
        try{draw.run();}
        catch(Throwable t){
            if(COMPLAINED.add(name))
                org.slf4j.LoggerFactory.getLogger("HexGodOfStories").error("Warping: the {} of a destination failed to draw",name,t);
        }
    }

    // ------------------------------------------------------------------ the pool, lying on the ground

    /**
     * One portal's pool, resolved onto the blocks it has run across.
     *
     * <p>A ring-and-spoke sheet rather than a set of loose polygons: the liquid is one continuous
     * surface, so it is built as one, and every vertex of it asks {@link WarpSurface} how high the
     * floor is in its own column. That is what lets a pool run up a step, over a slab and down a
     * stair and still lie on all of them — and a vertex whose column has no floor at all, because
     * the liquid reached a cliff edge or a hole, simply is not part of the sheet, so the pool stops
     * at the edge rather than hanging in the air past it.
     *
     * <p>It is lifted a fraction above the floor it covers, which is the difference between a pool
     * that is on the ground and a pool that is fighting the ground for the same pixels.
     *
     * <p>Rebuilt when the charge has spread it further, and re-sampled against the world a few times
     * a second so a floor that changes underneath an open portal is followed rather than ignored.
     */
    private static final class Break {
        final long seed;final int held;final boolean open;final long sampled;
        int steps,rings,count;
        /** Three doubles per vertex, ring-major, in portal-local blocks. */
        double[] v;
        /** Whether that vertex found a floor to lie on. */
        boolean[] there;
        /** The pool's own rim, and how far the furthest of it runs. */
        double[] rim;double extent;
        /** Surface height per block column, kept between rebuilds: the pool spreads, the floor does not. */
        Map<Long,Double> columns;
        /**
         * The colour of the floor, per block column, kept alongside its height.
         *
         * <p>What the liquid takes up as it spreads. A pool running over turf throws beads of green
         * and brown, one over a beach throws sand, one over stone throws grey, and one crossing the
         * line between two of them throws both. The map colour is the right source: every block in
         * the game has one, modded blocks included, so nothing has to be enumerated and nothing is
         * ever the wrong colour by default.
         */
        Map<Long,Integer> tints;
        Break(long seed,int held,boolean open,long sampled){this.seed=seed;this.held=held;this.open=open;this.sampled=sampled;}
        int index(int ring,int step){return ring*steps+Math.floorMod(step,steps);}
        boolean whole(int ring,int step){return there[index(ring,step)];}
        boolean face(int ring,int step){return whole(ring,step)&&whole(ring,step+1)&&whole(ring+1,step)&&whole(ring+1,step+1);}
        double x(int ring,int step){return v[index(ring,step)*3];}
        double y(int ring,int step){return v[index(ring,step)*3+1];}
        double z(int ring,int step){return v[index(ring,step)*3+2];}
        int tint(double lx,double lz,Vec3 origin){
            Integer known=tints.get(key(origin.x+lx,origin.z+lz));
            return known==null?0x6f6f6f:known;
        }
    }

    /** How far over the floor the liquid sits, so it covers the block rather than z-fighting it. */
    private static final double FILM=.045;
    /** Dense radial tessellation also keeps one-block terrain transitions tight instead of cutting broad holes. */
    private static final int MIN_RINGS=12,MAX_RINGS=48;

    private static Break shape(int id,CompoundTag n,Vec3 at,int held,boolean open,long now){
        long seed=n.getLong("seed");
        Break cached=BREAKS.get(id);
        // The spread is rebuilt whenever the charge has moved it on; the floor under it is asked
        // again a couple of times a second, which is often enough to follow a floor being changed.
        boolean closing=n.getBoolean("closing");
        boolean fresh=cached!=null&&cached.seed==seed&&now-cached.sampled<40&&!closing;
        if(fresh&&cached.held==held&&cached.open==open)return cached;
        ClientLevel level=Minecraft.getInstance().level;if(level==null)return cached;
        Break brk=new Break(seed,held,open,fresh?cached.sampled:now);
        brk.rim=WarpPool.rim(seed,WarpMath.reach(held),open?1:WarpMath.charge(held));
        if(closing){
            double collapse=closeScale(n,now);
            for(int i=0;i<brk.rim.length;i++)brk.rim[i]*=collapse;
        }
        brk.extent=WarpPool.extent(brk.rim);
        brk.steps=WarpPool.STEPS;
        brk.rings=Math.max(MIN_RINGS,Math.min(MAX_RINGS,(int)Math.round(brk.extent*1.35)));
        int vertices=(brk.rings+1)*brk.steps;
        brk.v=new double[vertices*3];brk.there=new boolean[vertices];
        Map<Long,Double> columns=fresh?cached.columns:new HashMap<>();
        Map<Long,Integer> tints=fresh?cached.tints:new HashMap<>();
        brk.columns=columns;brk.tints=tints;
        for(int ring=0;ring<=brk.rings;ring++){
            double f=ring/(double)brk.rings;
            for(int step=0;step<brk.steps;step++){
                double a=step*Math.PI*2/brk.steps;
                double r=brk.rim[step]*f;
                double lx=Math.cos(a)*r,lz=Math.sin(a)*r;
                int index=ring*brk.steps+step,o=index*3;
                brk.v[o]=lx;brk.v[o+2]=lz;
                // Destination-side emergence is a presentation puddle at the realm's existing
                // arrival coordinate. Source-side portals still follow real terrain exactly.
                double y=n.getBoolean("arrival")?at.y:column(level,columns,at.x+lx,at.z+lz,at.x,at.y,at.z);
                if(Double.isNaN(y))continue;
                if(!n.getBoolean("arrival"))tint(level,tints,at.x+lx,at.z+lz,y);
                brk.v[o+1]=y-at.y+FILM;
                brk.there[index]=true;
            }
        }
        for(int ring=0;ring<brk.rings;ring++)for(int step=0;step<brk.steps;step++)if(brk.face(ring,step))brk.count++;
        BREAKS.put(id,brk);
        return brk;
    }
    /** Surface height for one block column, cached so two vertices in one column agree exactly. */
    private static double column(ClientLevel level,Map<Long,Double> cache,double wx,double wz,double originX,double originY,double originZ){
        long key=key(wx,wz);
        Double known=cache.get(key);
        if(known!=null)return known;
        double y=WarpSurface.height(level,wx,wz,originX,originY,originZ);
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

    /** One vertex straight out of the sheet's own array. Allocating a Vec3 per corner, four times a
     *  frame across a couple of thousand faces, is the difference between free and noticeable. */
    private static void vertex(BufferBuilder b,Matrix4f m,double x,double y,double z,int colour,float alpha){
        b.vertex(m,(float)x,(float)y,(float)z)
            .color(((colour>>16)&255)/255f,((colour>>8)&255)/255f,(colour&255)/255f,alpha).endVertex();
    }
    private static void face(BufferBuilder b,Matrix4f m,Break brk,int ring,int step,int colour,float alpha){
        vertex(b,m,brk.x(ring,step),brk.y(ring,step),brk.z(ring,step),colour,alpha);
        vertex(b,m,brk.x(ring,step+1),brk.y(ring,step+1),brk.z(ring,step+1),colour,alpha);
        vertex(b,m,brk.x(ring+1,step+1),brk.y(ring+1,step+1),brk.z(ring+1,step+1),colour,alpha);
        vertex(b,m,brk.x(ring+1,step),brk.y(ring+1,step),brk.z(ring+1,step),colour,alpha);
    }

    /** The pool itself, as the stencil, the backing and the depth the destination is seen through. */
    private static void aperture(Matrix4f m,Break brk){
        BufferBuilder b=Tesselator.getInstance().getBuilder();b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        for(int ring=0;ring<brk.rings;ring++)for(int step=0;step<brk.steps;step++){
            if(!brk.face(ring,step))continue;
            face(b,m,brk,ring,step,0x000000,1);
        }
        BufferUploader.drawWithShader(b.end());
    }

    /**
     * A very dark translucent wash across the exact portal footprint.
     *
     * <p>This is presentation only and is intentionally drawn with the caller's ALWAYS depth mode:
     * cutout vegetation is terrain visually but has no collision, so it must look submerged without
     * changing WarpSurface, the stencil aperture or any server-side crossing test.
     */
    private static void groundFilm(Matrix4f m,Break brk,boolean open){
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b=Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        // One uninterrupted black skin. Never alternate colour by ring/step here: doing so exposes
        // the radial mesh as stripes, which is exactly the opposite of a continuous puddle.
        for(int ring=0;ring<brk.rings;ring++)for(int step=0;step<brk.steps;step++){
            if(!brk.face(ring,step))continue;
            face(b,m,brk,ring,step,0x010202,1.0f);
        }
        BufferUploader.drawWithShader(b.end());
    }

    /**
     * The visible body of the goo.
     *
     * <p>The aperture remains exactly on the floor because crossing physics are not presentation.
     * Everything drawn here sits above that authoritative plane: a low breathing body in the
     * middle, a steep shoulder over the outer third, a genuinely vertical outer wall, and a thick
     * rolled lip around the silhouette. The edge is intentionally the boldest part of the portal.
     */
    private static void surface(Matrix4f m,Break brk,boolean open,double age,int held,double time,int window,double closeScale){
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b=Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        double progress=open?1:WarpMath.charge(held);
        double settle=open?Math.min(1,age/28.0):0;
        double closing=open?Math.max(0,(age-(Math.max(18,window)-18))/18.0):0;

        // The body is one continuous colour. Shape, silhouette and height carry the form instead
        // of per-face shading, so the player sees one blob rather than the underlying quad grid.
        for(int ring=0;ring<brk.rings;ring++)for(int step=0;step<brk.steps;step++){
            if(!brk.face(ring,step))continue;
            double h00=gooHeight(brk,ring,step,time,progress,settle,closing)*closeScale;
            double h01=gooHeight(brk,ring,step+1,time,progress,settle,closing)*closeScale;
            double h11=gooHeight(brk,ring+1,step+1,time,progress,settle,closing)*closeScale;
            double h10=gooHeight(brk,ring+1,step,time,progress,settle,closing)*closeScale;
            raisedFace(b,m,brk,ring,step,h00,h01,h11,h10,0x070809,1.0f);
        }

        // A continuous rounded rim replaces the old per-segment box/tube construction. Every
        // neighbouring segment shares exactly the same profile vertices, so there are no fins,
        // overlaps or radial stripes. The crest is still deliberately the boldest part.
        roundedRim(b,m,brk,time,progress,settle,closing,closeScale);

        // Broad low blisters keep the centre volumetric without competing with the heavy border.
        int mounds=Math.max(8,Math.min(16,brk.rings/2+4));
        for(int i=0;i<mounds;i++){
            int step=Math.floorMod(i*29+(int)(brk.seed&63),brk.steps);
            int ring=1+Math.floorMod(i*7+(int)(brk.seed>>>10),Math.max(1,brk.rings-3));
            if(!brk.whole(ring,step))continue;
            double cycle=(time*(.005+.001*(i%3))+noise(brk.seed,i,7))%1.0;
            double breathe=.72+.28*Math.sin(cycle*Math.PI*2);
            double base=gooHeight(brk,ring,step,time,progress,settle,closing);
            double radius=(.28+.38*noise(brk.seed,i,8))*closeScale;
            double height=(.050+.070*noise(brk.seed,i,9))*closeScale;
            WarpMesh.sphere(b,m,new Vec3(brk.x(ring,step),brk.y(ring,step)+base+height*.16,brk.z(ring,step)),
                radius,height*breathe,radius,0x0d0f10,.24f,12,0,false);
        }

        // Sparse bubbles, rounded enough not to reveal the mesh underneath.
        int bubbles=Math.max(7,Math.min(18,brk.steps/6));
        for(int i=0;i<bubbles;i++){
            int step=Math.floorMod(i*37+(int)(brk.seed&31),brk.steps);
            int ring=2+Math.floorMod(i*11+(int)(brk.seed>>>8),Math.max(1,brk.rings-3));
            if(!brk.whole(ring,step))continue;
            double cycle=(time*(.009+.0015*(i%4))+noise(brk.seed,i,1))%1.0;
            if(cycle<.18||cycle>.90)continue;
            double dome=Math.max(0,Math.sin((cycle-.18)/.72*Math.PI));
            if(dome<=0)continue;
            double size=(.09+.14*noise(brk.seed,i,2))*closeScale;
            double base=gooHeight(brk,ring,step,time,progress,settle,closing);
            double y=brk.y(ring,step)+base+dome*(.025+.050*noise(brk.seed,i,3));
            WarpMesh.sphere(b,m,new Vec3(brk.x(ring,step),y,brk.z(ring,step)),
                size,size*.34*dome,size,0x151819,(float)(.22+.26*dome),10,0,false);
        }

        BufferUploader.drawWithShader(b.end());
    }

    /**
     * Rounded cross-section around the entire silhouette.
     *
     * <p>The old rim made one independent rectangular tube per edge segment. Those rectangles
     * overlapped at every turn of the outline and looked like a circular row of black fins. This
     * instead builds six shared profile rings and connects ring-to-ring, so the lip is one continuous
     * swollen roll of tar.
     */
    private static void roundedRim(BufferBuilder b,Matrix4f m,Break brk,double time,double progress,double settle,double closing,double closeScale){
        int edge=brk.rings;
        double[] offset={-.46,-.30,-.13,.04,.19,.31};
        for(int step=0;step<brk.steps;step++){
            int next=step+1;
            if(!brk.whole(edge,step)||!brk.whole(edge,next))continue;
            double h0=gooHeight(brk,edge,step,time,progress,settle,closing);
            double h1=gooHeight(brk,edge,next,time,progress,settle,closing);
            double a0=step*Math.PI*2/brk.steps;
            double a1=next*Math.PI*2/brk.steps;
            double lead0=WarpPool.advancing(brk.seed,a0,progress);
            double lead1=WarpPool.advancing(brk.seed,a1,progress);
            for(int band=0;band<offset.length-1;band++){
                Vec3 p0=rimProfile(brk,step,offset[band]*closeScale,rimProfileHeight(band,h0,progress,lead0)*closeScale,edge);
                Vec3 p1=rimProfile(brk,next,offset[band]*closeScale,rimProfileHeight(band,h1,progress,lead1)*closeScale,edge);
                Vec3 q1=rimProfile(brk,next,offset[band+1]*closeScale,rimProfileHeight(band+1,h1,progress,lead1)*closeScale,edge);
                Vec3 q0=rimProfile(brk,step,offset[band+1]*closeScale,rimProfileHeight(band+1,h0,progress,lead0)*closeScale,edge);
                int tone=band==2?0x121516:band==3?0x0b0d0e:band>=4?0x020303:0x080a0b;
                WarpMesh.quad(b,m,p0,p1,q1,q0,tone,1.0f);
            }
        }
    }

    /** Height of one cross-section lane. The outermost lane deliberately falls almost to the floor. */
    private static double rimProfileHeight(int lane,double edgeHeight,double progress,double lead){
        return switch(lane){
            case 0 -> Math.max(.08,edgeHeight-.07);
            case 1 -> edgeHeight+.025+.020*lead;
            case 2 -> edgeHeight+.115+.045*progress+.035*lead;
            case 3 -> edgeHeight+.145+.055*progress+.050*lead;
            case 4 -> edgeHeight+.055+.025*lead;
            default -> .022;
        };
    }

    /** One shared point on the rounded lip, offset perpendicular to the irregular outline. */
    private static Vec3 rimProfile(Break brk,int step,double offset,double height,int edge){
        Vec3 normal=rimNormal(brk,step,edge);
        return new Vec3(brk.x(edge,step)+normal.x*offset,
            brk.y(edge,step)+height,
            brk.z(edge,step)+normal.z*offset);
    }

    /** Smooth outward normal from neighbouring rim vertices; no independent segment-side vectors. */
    private static Vec3 rimNormal(Break brk,int step,int edge){
        int prev=Math.floorMod(step-1,brk.steps),next=Math.floorMod(step+1,brk.steps);
        double tx=brk.x(edge,next)-brk.x(edge,prev);
        double tz=brk.z(edge,next)-brk.z(edge,prev);
        double len=Math.sqrt(tx*tx+tz*tz);
        if(len<1.0E-7){
            double x=brk.x(edge,step),z=brk.z(edge,step),r=Math.sqrt(x*x+z*z);
            return r<1.0E-7?new Vec3(1,0,0):new Vec3(x/r,0,z/r);
        }
        double nx=-tz/len,nz=tx/len;
        double x=brk.x(edge,step),z=brk.z(edge,step);
        if(nx*x+nz*z<0){nx=-nx;nz=-nz;}
        return new Vec3(nx,0,nz);
    }

    /** Height above the authoritative floor plane. The edge swells, but only with broad waves. */
    private static double gooHeight(Break brk,int ring,int step,double time,double progress,double settle,double closing){
        double f=Math.max(0,Math.min(1,ring/(double)Math.max(1,brk.rings)));
        double a=step*Math.PI*2/brk.steps;
        double lead=WarpPool.advancing(brk.seed,a,progress);
        // Only low-frequency angular motion. High-frequency per-step wobble was exposing every
        // tessellation segment as a stripe.
        double slow=Math.sin(a*1.7+time*.028+(brk.seed&15))*.42
            +Math.sin(a*3.4-time*.018+(brk.seed>>>4&15))*.20;
        double body=.040+.015*(slow+.65)+.018*Math.sin(f*5.2-time*.040);
        double shoulder=smooth((f-.56)/.44);
        double rim=smooth((f-.80)/.20);
        double breathing=(1-settle*.60)*(.010*Math.sin(time*.065+a*2.2));
        return Math.max(.022,body
            +shoulder*(.080+.090*progress)
            +rim*(.105+.065*progress+.050*lead)
            +breathing*rim
            +closing*.045*rim);
    }

    private static double smooth(double x){
        x=Math.max(0,Math.min(1,x));
        return x*x*(3-2*x);
    }

    /** Raised counterpart of {@link #face}; the base geometry stays untouched for crossing/stencil. */
    private static void raisedFace(BufferBuilder b,Matrix4f m,Break brk,int ring,int step,
                                   double h00,double h01,double h11,double h10,int colour,float alpha){
        vertex(b,m,brk.x(ring,step),brk.y(ring,step)+h00,brk.z(ring,step),colour,alpha);
        vertex(b,m,brk.x(ring,step+1),brk.y(ring,step+1)+h01,brk.z(ring,step+1),colour,alpha);
        vertex(b,m,brk.x(ring+1,step+1),brk.y(ring+1,step+1)+h11,brk.z(ring+1,step+1),colour,alpha);
        vertex(b,m,brk.x(ring+1,step),brk.y(ring+1,step)+h10,brk.z(ring+1,step),colour,alpha);
    }

    /** Viscous collapse back into the exact centre instead of disappearing on one frame. */
    private static double closeScale(CompoundTag n,double time){
        if(!n.getBoolean("closing"))return 1;
        double t=Math.max(0,Math.min(1,(time-n.getLong("closeStart"))/CLOSE_TICKS));
        double eased=t*t*(3-2*t);
        return Math.max(0,1-eased);
    }

    /** A stable number in nought-to-one for one bead and one of its properties. */
    private static double noise(long seed,int index,int slot){
        long h=seed*0x9E3779B97F4A7C15L+index*0x632BE59BD9B4E019L+slot*0x4F1BBCDDL;
        h^=h>>>29;h*=0x94D049BB133111EBL;h^=h>>>32;
        return (h>>>11)/(double)(1L<<53);
    }
}
