package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Remove the previous release's terrain from loaded saves without a dimension reset. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID)
public final class RadialRealmRules {
    private static final ConcurrentLinkedQueue<Cleanup> QUEUE=new ConcurrentLinkedQueue<>();
    private static final class Cleanup {
        final LevelChunk chunk;int cursor;
        Cleanup(LevelChunk chunk){this.chunk=chunk;}
    }
    @SubscribeEvent public static void loaded(ChunkEvent.Load event) {
        if(!(event.getChunk() instanceof LevelChunk chunk)||!(chunk.getLevel() instanceof ServerLevel level))return;
        Destination d=Destination.from(level);
        if(d==Destination.GRAVITY_WELL||d==Destination.CRUSHING_REALM)QUEUE.add(new Cleanup(chunk));
    }
    public static void clean(ServerLevel level) {
        int budget=8192;
        for(Cleanup job:QUEUE) {
            if(job.chunk.getLevel()!=level)continue;
            boolean well=Destination.from(level)==Destination.GRAVITY_WELL;
            int total=well?job.chunk.getSections().length*4096:256;
            while(job.cursor<total&&budget>0) {
                if(well&&job.cursor%4096==0&&job.chunk.getSections()[job.cursor/4096].hasOnlyAir()) {job.cursor+=4096;continue;}
                int i=job.cursor++;budget--;
                int x=(job.chunk.getPos().x<<4)+(i&15),z=(job.chunk.getPos().z<<4)+((i>>4)&15);
                int y=well?level.getMinBuildHeight()+(i>>8):98;
                if(!well&&(Math.abs(x)>42||Math.abs(z)>42))continue;
                BlockPos pos=new BlockPos(x,y,z);
                if(!job.chunk.getBlockState(pos).isAir())level.setBlock(pos,Blocks.AIR.defaultBlockState(),2|16);
            }
            if(job.cursor>=total)QUEUE.remove(job);
            if(budget<=0)break;
        }
    }
    public static void clear(){QUEUE.clear();}
    private RadialRealmRules() {}
}
