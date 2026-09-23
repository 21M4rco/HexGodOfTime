package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.warping.CandyCorruption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

@Mod.EventBusSubscriber(modid=HexGodOfStories.ID,value=Dist.CLIENT)
public final class CandyCorruptionClient {
    private CandyCorruptionClient(){}
    private record State(int mask,int breaking,long start,int duration){}
    private record Visibility(boolean ra,boolean ras,boolean la,boolean las,boolean rl,boolean rp,boolean ll,boolean lp){}
    private static final Map<Integer,State> STATES=new HashMap<>();
    private static final Map<Integer,Visibility> HIDDEN=new HashMap<>();

    public static void receive(int id,CompoundTag n){
        int mask=n.getInt("mask"),breaking=n.getInt("breaking");
        if(mask==0&&breaking<0)STATES.remove(id);
        else STATES.put(id,new State(mask,breaking,n.getLong("start"),Math.max(1,n.getInt("breakTicks"))));
        if(n.contains("burst"))burst(id,n.getInt("burst"));
    }
    public static boolean broken(int id,int part){State s=STATES.get(id);return s!=null&&(s.mask&(1<<part))!=0;}
    public static boolean skinActive(int id){State s=STATES.get(id);return s!=null&&s.breaking>=0;}
    private static float progress(State s){return s==null||s.breaking<0?0:Math.max(0,Math.min(1,(ClientState.now()-s.start)/(float)s.duration));}
    public static int skinState(int id){
        State s=STATES.get(id);if(s==null)return 0;
        int bucket=Math.round(progress(s)*31);
        return (s.mask&15)|(((s.breaking+1)&7)<<4)|(bucket<<7);
    }
    public static int tint(int id,int abgr,int x,int y){
        State s=STATES.get(id);
        if(s==null||s.breaking<0||partAt(x,y)!=s.breaking)return abgr;
        if((abgr>>>24&255)==0)return abgr;
        float p=progress(s);
        int pink=OutfitPattern.abgr(255,92+(int)(35*p),177+(int)(28*p));
        int out=blend(abgr,pink,.18f+.78f*p);
        if(p>.52f){
            int hash=Math.floorMod(x*31+y*17+s.breaking*13,23);
            int threshold=(int)((p-.52f)*34);
            if(hash<threshold)out=blend(out,OutfitPattern.abgr(86,10,55),Math.min(.9f,(p-.45f)*1.5f));
        }
        return out;
    }
    private static int blend(int a,int b,float t){
        float k=Math.max(0,Math.min(1,t));int out=0;
        for(int shift=0;shift<32;shift+=8){int av=a>>>shift&255,bv=b>>>shift&255;out|=Math.round(av+(bv-av)*k)<<shift;}
        return out;
    }
    private static int partAt(int x,int y){
        if(y>=16&&y<32&&x>=40&&x<56)return CandyCorruption.RIGHT_ARM;
        if(y>=32&&y<48&&x>=40&&x<56)return CandyCorruption.RIGHT_ARM;
        if(y>=48&&y<64&&((x>=32&&x<48)||(x>=48&&x<64)))return CandyCorruption.LEFT_ARM;
        if(y>=16&&y<32&&x>=0&&x<16)return CandyCorruption.RIGHT_LEG;
        if(y>=32&&y<48&&x>=0&&x<16)return CandyCorruption.RIGHT_LEG;
        if(y>=48&&y<64&&((x>=16&&x<32)||(x>=0&&x<16)))return CandyCorruption.LEFT_LEG;
        return -1;
    }

    @SubscribeEvent public static void pre(RenderPlayerEvent.Pre e){
        AbstractClientPlayer p=e.getEntity();State s=STATES.get(p.getId());if(s==null||s.mask==0)return;
        PlayerModel<AbstractClientPlayer> m=e.getRenderer().getModel();
        HIDDEN.put(p.getId(),new Visibility(m.rightArm.visible,m.rightSleeve.visible,m.leftArm.visible,m.leftSleeve.visible,m.rightLeg.visible,m.rightPants.visible,m.leftLeg.visible,m.leftPants.visible));
        if((s.mask&1<<CandyCorruption.RIGHT_ARM)!=0){m.rightArm.visible=false;m.rightSleeve.visible=false;}
        if((s.mask&1<<CandyCorruption.LEFT_ARM)!=0){m.leftArm.visible=false;m.leftSleeve.visible=false;}
        if((s.mask&1<<CandyCorruption.RIGHT_LEG)!=0){m.rightLeg.visible=false;m.rightPants.visible=false;}
        if((s.mask&1<<CandyCorruption.LEFT_LEG)!=0){m.leftLeg.visible=false;m.leftPants.visible=false;}
    }
    @SubscribeEvent public static void post(RenderPlayerEvent.Post e){
        Visibility v=HIDDEN.remove(e.getEntity().getId());if(v==null)return;
        PlayerModel<AbstractClientPlayer> m=e.getRenderer().getModel();
        m.rightArm.visible=v.ra;m.rightSleeve.visible=v.ras;m.leftArm.visible=v.la;m.leftSleeve.visible=v.las;
        m.rightLeg.visible=v.rl;m.rightPants.visible=v.rp;m.leftLeg.visible=v.ll;m.leftPants.visible=v.lp;
    }

    private static void burst(int id,int part){
        Minecraft mc=Minecraft.getInstance();if(mc.level==null)return;
        Entity e=mc.level.getEntity(id);if(e==null)return;
        double yaw=Math.toRadians(e.getYRot());Vec3 right=new Vec3(Math.cos(yaw),0,Math.sin(yaw));
        boolean r=part==CandyCorruption.RIGHT_ARM||part==CandyCorruption.RIGHT_LEG;
        double side=(part<=CandyCorruption.LEFT_ARM?.34:.14)*(r?1:-1),y=part<=CandyCorruption.LEFT_ARM?1.30:.56;
        Vec3 at=e.position().add(right.scale(side)).add(0,y,0);
        for(int i=0;i<20;i++)mc.level.addParticle(HexGodOfStories.CANDY.get(),
            at.x+(mc.level.random.nextDouble()-.5)*.18,at.y+(mc.level.random.nextDouble()-.5)*.28,at.z+(mc.level.random.nextDouble()-.5)*.18,
            (mc.level.random.nextDouble()-.5)*.12,.02-mc.level.random.nextDouble()*.12,(mc.level.random.nextDouble()-.5)*.12);
    }
    public static void clear(){STATES.clear();HIDDEN.clear();}
}
