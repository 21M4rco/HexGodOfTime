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

/** Opaque liquid portals, clipped to individual collision tops and depth-tested against the world. */
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
        if(camera.distanceToSqr(at)>96*96)return;
        boolean open=n.getLong("opened")>=0,closing=n.getBoolean("closing");
        int held=closing?n.getInt("closeHeld"):(open?n.getInt("held"):(int)(time-n.getLong("start")));
        held=Math.min(held,WarpMath.FULL_CHARGE);
        Break brk=shape(id,n,at,held,open,(long)time);
        if(brk==null||brk.patches.isEmpty())return;
        pose.pushPose();pose.translate(at.x-camera.x,at.y-camera.y,at.z-camera.z);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableCull();
        RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);RenderSystem.disableBlend();
        try{
            // This portal is opaque: its own depth-tested skin hides submerged body geometry.
            // No framebuffer depth reset, stencil clear or GL_ALWAYS overlay is needed. Foreground
            // trunks, walls and players keep their ordinary world depth throughout this pass.
            BufferBuilder b=Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);
            double collapse=closing?closeScale(n,time):1;
            for(Patch patch:brk.patches){
                var points=patch.points;
                for(int i=1;i<points.size()-1;i++){
                    liquidVertex(b,pose.last().pose(),brk,points.get(0),patch.y,time,collapse);
                    liquidVertex(b,pose.last().pose(),brk,points.get(i),patch.y,time,collapse);
                    liquidVertex(b,pose.last().pose(),brk,points.get(i+1),patch.y,time,collapse);
                }
            }
            BufferUploader.drawWithShader(b.end());
        }finally{
            pose.popPose();RenderSystem.depthMask(true);RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.enableCull();
            RenderSystem.disableBlend();RenderSystem.setShaderColor(1,1,1,1);
        }
    }

    private record Patch(java.util.List<WarpTessellation.Point> points,double y) {}
    private static final class Break {
        final long seed,sampled;final int held;final boolean open,arrival;final Vec3 origin;
        final java.util.List<Patch> patches=new ArrayList<>();
        final Map<Long,java.util.List<WarpSurface.Tile>> columns;
        double[] rim;
        Break(long seed,long sampled,int held,boolean open,boolean arrival,Vec3 origin,
              Map<Long,java.util.List<WarpSurface.Tile>> columns){
            this.seed=seed;this.sampled=sampled;this.held=held;this.open=open;this.arrival=arrival;
            this.origin=origin;this.columns=columns;
        }
    }
    private static final double FILM=.025;

    private static Break shape(int id,CompoundTag n,Vec3 at,int held,boolean open,long now){
        long seed=n.getLong("seed");boolean arrival=n.getBoolean("arrival"),closing=n.getBoolean("closing");
        Break cached=BREAKS.get(id);
        boolean fresh=cached!=null&&cached.seed==seed&&cached.origin.equals(at)&&cached.arrival==arrival
            &&now>=cached.sampled&&now-cached.sampled<5;
        if(fresh&&!closing&&cached.held==held&&cached.open==open)return cached;
        ClientLevel level=Minecraft.getInstance().level;if(level==null)return null;
        Break brk=new Break(seed,fresh?cached.sampled:now,held,open,arrival,at,
            fresh?cached.columns:new HashMap<>());
        brk.rim=WarpPool.rim(seed,WarpMath.reach(held),open?1:WarpMath.charge(held));
        double collapse=closing?closeScale(n,now):1;
        for(int i=0;i<brk.rim.length;i++)brk.rim[i]*=collapse;
        // Each fan sector is clipped at BLOCK/COLLISION boundaries before assigning a height.
        // No polygon can interpolate a ramp through a cube or leave a triangular hole at a step.
        for(int i=0;i<brk.rim.length;i++){
            int j=(i+1)%brk.rim.length;
            double a=i*Math.PI*2/brk.rim.length,c=j*Math.PI*2/brk.rim.length;
            double ax=Math.cos(a)*brk.rim[i],az=Math.sin(a)*brk.rim[i];
            double bx=Math.cos(c)*brk.rim[j],bz=Math.sin(c)*brk.rim[j];
            int minX=Mth.floor(at.x+Math.min(0,Math.min(ax,bx)));
            int maxX=Mth.floor(at.x+Math.max(0,Math.max(ax,bx)));
            int minZ=Mth.floor(at.z+Math.min(0,Math.min(az,bz)));
            int maxZ=Mth.floor(at.z+Math.max(0,Math.max(az,bz)));
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
                // Reject empty sector/column intersections before sampling blocks.
                if(WarpTessellation.area(WarpTessellation.clip(ax,az,bx,bz,x-at.x,z-at.z,x+1-at.x,z+1-at.z))<1E-10)continue;
                long key=((long)x<<32)^(z&0xffffffffL);
                var tiles=brk.columns.get(key);
                if(tiles==null){
                    if(arrival){
                        tiles=new ArrayList<>();
                        for(int sx=0;sx<2;sx++)for(int sz=0;sz<2;sz++)
                            tiles.add(new WarpSurface.Tile(x+sx*.5,z+sz*.5,x+(sx+1)*.5,z+(sz+1)*.5,at.y));
                    }else tiles=WarpSurface.tiles(level,x,z,at.x,at.y,at.z);
                    brk.columns.put(key,tiles);
                }
                for(var tile:tiles){
                    var polygon=WarpTessellation.clip(ax,az,bx,bz,tile.minX()-at.x,tile.minZ()-at.z,
                        tile.maxX()-at.x,tile.maxZ()-at.z);
                    if(WarpTessellation.area(polygon)>1E-10)brk.patches.add(new Patch(polygon,tile.y()-at.y+FILM));
                }
            }
        }
        BREAKS.put(id,brk);return brk;
    }

    /** Continuous position-based waves and broad moving reflections, never per-face stripes. */
    private static void liquidVertex(BufferBuilder b,Matrix4f m,Break brk,WarpTessellation.Point p,
                                     double floor,double time,double collapse){
        double x=p.x(),z=p.z(),phase=(brk.seed&255)*.024;
        double radius=Math.hypot(x,z);
        double edge=Math.max(0,WarpPool.radius(brk.rim,Math.atan2(z,x))-radius);
        double meniscus=Math.exp(-edge*edge*32)*.018;
        double a=x*.85+z*.42-time*.065+phase;
        double c=x*-.38+z*1.12+time*.048;
        double wave=Math.sin(a)*.008+Math.sin(c)*.006;
        double ripple=Math.sin(radius*2.2-time*.11+phase)*.003;
        double height=(.021+wave+ripple+meniscus)*collapse;
        // Wide, muted teal-grey reflections keep black liquid readable without glowing lines.
        double shine=Math.pow(Math.max(0,.5+.28*Math.cos(a)+.22*Math.cos(c)),5);
        double rim=Math.exp(-edge*edge*70)*.025;
        float red=(float)(.014+shine*.045+rim);
        float green=(float)(.019+shine*.062+rim*1.15);
        float blue=(float)(.022+shine*.067+rim*1.18);
        b.vertex(m,(float)x,(float)(floor+height),(float)z).color(red,green,blue,1).endVertex();
    }

    private static double closeScale(CompoundTag n,double time){
        double t=Math.max(0,Math.min(1,(time-n.getLong("closeStart"))/CLOSE_TICKS));
        return 1-t*t*(3-2*t);
    }
}
