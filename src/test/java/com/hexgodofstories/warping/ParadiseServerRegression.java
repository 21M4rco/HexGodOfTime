package com.hexgodofstories.warping;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Real Forge checks for the Paradise-specific interactions. Test source is excluded from the mod. */
@Mod.EventBusSubscriber(modid=HexGodOfStories.ID)
public final class ParadiseServerRegression {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        ServerLevel level=event.getServer().getLevel(Destination.PARADISE.key);
        check(level!=null,"Paradise loads");
        var blueprint=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var voxel:RealmLayout.blocks(Destination.PARADISE))blueprint.put(voxel.pos(),voxel.state());
        check(blueprint.size()>110000&&blueprint.size()<200000,"kingdom generation stays bounded");
        for(int y=161;y<=192;y++)check(!blueprint.containsKey(new BlockPos(0,y,28)),"entry stays clear");
        check(blueprint.get(new BlockPos(0,160,28)).is(Blocks.SMOOTH_QUARTZ),"arrival has a solid foundation");
        check(blueprint.values().stream().filter(s->s.is(Blocks.LANTERN)).count()>20,"bridges and buildings have lanterns");
        check(Paradise.SPRING_DEPTH==2,"ponds stay shallow");
        check(blueprint.values().stream().filter(s->s.is(Blocks.SPRUCE_FENCE)).count()<=48,
            "bridge rails use solid block edges rather than spruce fences");
        long flowers=blueprint.values().stream().filter(s->s.is(Blocks.PINK_TULIP)||s.is(Blocks.WHITE_TULIP)
            ||s.is(Blocks.ALLIUM)||s.is(Blocks.LILY_OF_THE_VALLEY)||s.is(Blocks.OXEYE_DAISY)
            ||s.is(Blocks.CORNFLOWER)||s.is(Blocks.AZURE_BLUET)||s.is(Blocks.PINK_PETALS)
            ||s.is(Blocks.FLOWERING_AZALEA)).count();
        check(flowers>180,"the islands have dense garden detail");

        // No surface pool may occupy a bridge approach. This is the exact geometry regression that
        // previously made the tower-island pond merge into the wooden bridge.
        for(Paradise.Bridge bridge:Paradise.BRIDGES)for(int isleIndex:new int[]{bridge.from(),bridge.to()}) {
            Paradise.Isle isle=Paradise.isles().get(isleIndex);
            Paradise.Isle other=Paradise.isles().get(isleIndex==bridge.from()?bridge.to():bridge.from());
            Vec3 end=Paradise.bridgeEnd(isle,other);
            for(var e:blueprint.entrySet()) {
                BlockPos p=e.getKey();
                if(!e.getValue().is(Blocks.WATER)||Math.abs(p.getY()-isle.y())>2)continue;
                check(Math.hypot(p.getX()+.5-end.x,p.getZ()+.5-end.z)>6.5,
                    "bridge approach "+isleIndex+" stays clear of pond water");
            }
        }

        long rearDetail=blueprint.entrySet().stream().filter(e->{
            BlockPos p=e.getKey();var s=e.getValue();
            if(p.getX()<-15||p.getX()>15||p.getZ()<-38||p.getZ()>-24||p.getY()<161||p.getY()>170)return false;
            return s.is(Blocks.PINK_TULIP)||s.is(Blocks.WHITE_TULIP)||s.is(Blocks.ALLIUM)
                ||s.is(Blocks.LILY_OF_THE_VALLEY)||s.is(Blocks.OXEYE_DAISY)||s.is(Blocks.CORNFLOWER)
                ||s.is(Blocks.AZURE_BLUET)||s.is(Blocks.PINK_PETALS)||s.is(Blocks.FLOWERING_AZALEA)
                ||s.is(Blocks.PINK_CONCRETE)||s.is(Blocks.MAGENTA_CONCRETE)||s.is(Blocks.QUARTZ_PILLAR)
                ||s.is(Blocks.LANTERN);
        }).count();
        check(rearDetail>45,"the rear of the castle is a decorated garden rather than empty lawn");

        // Dump a reproducible overview for visual inspection of the actual generated blueprint.
        try {
            var lines=new ArrayList<String>();
            blueprint.forEach((p,s)->lines.add(p.getX()+","+p.getY()+","+p.getZ()+","+
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock())));
            java.nio.file.Files.write(java.nio.file.Path.of("paradise-blueprint.csv"),lines);
        } catch(java.io.IOException ex) {throw new IllegalStateException(ex);}

        ItemStack water=ParadiseWaters.create();
        check(water.is(Items.POTION)&&water.getHoverName().getString().equals("Paradise Waters"),"bottle name and item");
        check(ParadiseWaters.isParadiseWaters(water),"Paradise Waters carries consumption provenance");
        check(PotionUtils.getColor(water)==0xff69c8,"bottled water is pink");
        check(PotionUtils.getMobEffects(water).stream().anyMatch(e->e.getEffect()==HexGodOfStories.CANDY_RUSH.get()
            &&e.getDuration()==6000),"bottle supplies five minutes of Candy Rush");

        FakePlayer player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"ParadiseRegression"));
        BlockPos support=new BlockPos(0,160,28),placed=support.above();
        level.setBlock(support,Blocks.SMOOTH_QUARTZ.defaultBlockState(),2|16);
        level.setBlock(placed,Blocks.AIR.defaultBlockState(),2|16);
        player.moveTo(3,161,28);
        ItemStack food=new ItemStack(Items.PINK_CONCRETE,1);ParadiseFood.mark(food);
        ItemStack ordinary=new ItemStack(Items.PINK_CONCRETE,1);
        check(!ItemStack.isSameItemSameTags(food,ordinary),"Paradise candy blocks do not stack with ordinary blocks");
        check(food.getHoverName().getString().equals("Candy Pink Concrete"),"Paradise block name gets Candy prefix");
        check(CandyCorruption.DOSES_PER_LIMB==5&&CandyCorruption.BREAK_TICKS>=30,"five mouthfuls start one visible limb failure");
        player.setItemInHand(InteractionHand.MAIN_HAND,food);
        var hit=new BlockHitResult(Vec3.atCenterOf(support).add(0,.5,0),Direction.UP,support,false);
        var result=ForgeHooks.onPlaceItemIntoWorld(new UseOnContext(player,InteractionHand.MAIN_HAND,hit));
        check(result.consumesAction()&&level.getBlockState(placed).is(Blocks.PINK_CONCRETE),"last edible block places normally");
        CompoundTag saved=ParadiseRestoration.of(level).save(new CompoundTag());
        check(Arrays.stream(saved.getLongArray("placed_edible")).anyMatch(p->p==placed.asLong()),"placed food provenance persists");
        check(Arrays.stream(ParadiseRestoration.load(saved).save(new CompoundTag()).getLongArray("placed_edible"))
            .anyMatch(p->p==placed.asLong()),"food provenance survives reload");
        ParadiseRestoration.broken(new BlockEvent.BreakEvent(level,placed,level.getBlockState(placed),player));
        level.destroyBlock(placed,true,player);
        check(level.getEntitiesOfClass(ItemEntity.class,new net.minecraft.world.phys.AABB(placed).inflate(2))
            .stream().anyMatch(e->ParadiseFood.edible(e.getItem())),"break-place-break keeps the drop edible");

        var pig=EntityType.PIG.create(level);check(pig!=null,"rescue subject exists");
        pig.moveTo(0,Paradise.FLOOR-5,28);level.addFreshEntity(pig);
        ParadiseRules.enforce(level,pig);
        check(pig.getY()>=161&&pig.fallDistance==0&&pig.getDeltaMovement().lengthSqr()==0,"void returns stop falling");
        check(level.noCollision(pig,pig.getBoundingBox()),"rescue leaves the body clear of blocks");
        pig.moveTo(250,180,0);ParadiseRules.enforce(level,pig);
        check(Math.hypot(pig.getX(),pig.getZ())<Paradise.BOUNDARY,"live entity folds at boundary");
        var item=new ItemEntity(level,4,164,28,new ItemStack(Items.SUGAR));level.addFreshEntity(item);
        WarpResidency.track(item,new UUID(0,0));WarpResidency.tick(level);
        check(WarpResidency.known(level,item.getUUID()),"loose items retain residency tickets");
        item.discard();WarpResidency.tick(level);
        check(!WarpResidency.known(level,item.getUUID()),"removed items release tickets");
        pig.discard();
        System.out.println("PARADISE_SERVER_REGRESSIONS_PASSED");
    }
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError("Paradise: "+why);}
}
