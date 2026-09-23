package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.server.HexServer;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** Dedicated-server lifecycle and ordinary-player input checks; no client classes loaded. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID)
public final class WarpLifecycleRegression {
    private static boolean done;
    private static int waited;
    private static net.minecraft.world.entity.animal.Pig pig;
    private static WarpCrossing.Break brk;
    @SubscribeEvent public static void tick(TickEvent.LevelTickEvent event) throws Exception {
        if(done||event.phase!=TickEvent.Phase.END||!(event.level instanceof ServerLevel level)
            ||level.dimension()!=Level.OVERWORLD)return;
        // The synthetic test has no connected player to make this chunk entity-ticking.
        // Install its ticket, then allow normal chunk/entity-manager promotion before querying it.
        if(pig==null){
            level.setChunkForced(0,0,true);
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)
                level.setBlock(new BlockPos(x,250,z),Blocks.STONE.defaultBlockState(),2|16);
            brk=new WarpCrossing.Break(UUID.randomUUID(),level,new Vec3(.5,251.025,.5),
                Destination.VOID_SEA,0,40,WarpPool.rim(42,5,1),5,new HashSet<>());
            pig=EntityType.PIG.create(level);check(pig!=null,"pig created");
            pig.setNoAi(true);pig.setNoGravity(true);
            pig.moveTo(.5,251,.5);check(level.addFreshEntity(pig),"pig added");
            return;
        }
        if(level.getEntity(pig.getUUID())!=pig){
            check(++waited<80,"test chunk promotes its entity");return;
        }
        done=true;
        WarpCrossing.tick(brk,level.getGameTime(),e->e==pig);
        check(WarpCrossing.crossing(pig)&&pig.noPhysics,"portal offers no-collision grant");
        pig.moveTo(40,251,40);
        WarpCrossing.tick(brk,level.getGameTime()+1,e->e==pig);
        check(!WarpCrossing.crossing(pig)&&!pig.noPhysics,"leaving query volume releases grant");
        pig.moveTo(.5,251,.5);
        WarpCrossing.tick(brk,level.getGameTime()+2,e->e==pig);
        check(WarpCrossing.crossing(pig),"second passage begins");
        WarpCrossing.tick(brk,level.getGameTime()+3,e->false);
        check(!WarpCrossing.crossing(pig)&&!pig.noPhysics,"eligibility loss releases grant");

        // Fake players do not join the player list/query; call the same offer the sweep invokes.
        FakePlayer ordinary=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"TrappedOrdinary"));
        HexData.access(ordinary,false);ordinary.moveTo(.5,251,.5);
        var offer=WarpCrossing.class.getDeclaredMethod("offer",WarpCrossing.Break.class,Entity.class,long.class);
        offer.setAccessible(true);offer.invoke(null,brk,ordinary,level.getGameTime());
        check(WarpCrossing.crossing(ordinary),"ordinary player can be trapped");
        HexServer.input(ordinary,HexServer.WARP_STRUGGLE,0);
        var passages=WarpCrossing.class.getDeclaredField("PASSAGES");passages.setAccessible(true);
        Object passage=((Map<?,?>)passages.get(null)).get(ordinary.getUUID());
        var struggle=passage.getClass().getDeclaredField("struggle");struggle.setAccessible(true);
        check(struggle.getDouble(passage)>0,"ordinary player input banks struggle lift");
        WarpCrossing.forget(ordinary);
        check(!WarpCrossing.crossing(ordinary)&&!ordinary.noPhysics,"player lifecycle releases passage");

        pig.setNoGravity(false);pig.setNoAi(false);
        WarpEmergence.begin(pig,new Vec3(.5,251,.5),Vec3.ZERO,false);
        check(pig.isNoGravity()&&pig.isNoAi(),"emergence borrows gravity and AI");
        check(WarpCrossing.sinking(pig),"living movement hook honours the emergence grant");
        WarpEmergence.cancel(pig);
        check(!pig.isNoGravity()&&!pig.isNoAi()&&!pig.noPhysics,"cancel restores original flags");
        pig.setNoGravity(true);pig.setNoAi(true);
        WarpEmergence.begin(pig,new Vec3(.5,251,.5),Vec3.ZERO,false);
        WarpEmergence.reset();
        check(pig.isNoGravity()&&pig.isNoAi()&&!pig.noPhysics,"reset preserves pre-existing flags");
        pig.discard();level.setChunkForced(0,0,false);
        System.out.println("WARP_LIFECYCLE_REGRESSIONS_PASSED");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
