package com.hexgodofstories.server;
import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=HexGodOfStories.ID)
public final class HexCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent e) {
        var root=Commands.literal("hgos").requires(s->s.hasPermission(2));
        root.then(Commands.literal("unlock").then(Commands.argument("player",EntityArgument.player())
            .then(Commands.literal("on").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");HexServer.access(p,true);c.getSource().sendSuccess(()->Component.literal("Powers enabled for "+p.getGameProfile().getName()+"."),true);return 1;}))
            .then(Commands.literal("off").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");HexServer.access(p,false);c.getSource().sendSuccess(()->Component.literal("Powers disabled for "+p.getGameProfile().getName()+"."),true);return 1;}))));
        var player=Commands.argument("player",EntityArgument.player());
        for(String verb:new String[]{"set","add"}) {
            var node=Commands.literal(verb);
            for(Discipline d:Discipline.values())node.then(Commands.literal(d.name().toLowerCase()).then(Commands.argument("level",IntegerArgumentType.integer(0,1000)).executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");int n=IntegerArgumentType.getInteger(c,"level");HexData.mastery(p,d,verb.equals("add")?HexData.mastery(p,d)+n:n);HexNetwork.sync(p);return 1;})));
            player.then(node);
        }
        var unlock=Commands.literal("unlock");
        unlock.then(Commands.literal("all").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");for(Discipline d:Discipline.values())HexData.mastery(p,d,1000);for(Ability a:Ability.values())HexData.get(p).putBoolean("unlock_"+a.name(),true);HexData.energy(p,HexData.maxEnergy(p));HexNetwork.sync(p);return 1;}));
        for(Ability a:Ability.values())unlock.then(Commands.literal(a.name().toLowerCase()).executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");HexData.get(p).putBoolean("unlock_"+a.name(),true);HexNetwork.sync(p);return 1;}));
        player.then(unlock);
        player.then(Commands.literal("energy").then(Commands.argument("amount",FloatArgumentType.floatArg(0,1000)).executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");HexData.energy(p,FloatArgumentType.getFloat(c,"amount"));HexNetwork.sync(p);return 1;})));
        player.then(Commands.literal("transform").then(Commands.argument("active",BoolArgumentType.bool()).executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");if(!HexData.access(p)){c.getSource().sendFailure(Component.literal("That player does not have powers enabled."));return 0;}HexData.get(p).putBoolean("ascended",BoolArgumentType.getBool(c,"active"));HexData.get(p).putLong("transformStart",HexData.now(p));HexNetwork.fx(p,"ascend");HexNetwork.sync(p);return 1;})));
        player.then(Commands.literal("reset").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");HexServer.clear(p,true);p.getPersistentData().remove(HexData.TAG);HexNetwork.sync(p);return 1;}));
        player.then(Commands.literal("clear_illusions").executes(c->{HexServer.clearIllusions(EntityArgument.getPlayer(c,"player"));return 1;}));
        player.then(Commands.literal("clear_time").executes(c->{TemporalEngine.clear(EntityArgument.getPlayer(c,"player"));return 1;}));
        player.then(Commands.literal("clear_bleed").executes(c->{Bleed.clear(EntityArgument.getPlayer(c,"player"));return 1;}));
        // Drops a charge that is being held and hands back anything caught mid-erasure, for testing and
        // for the rare case an operator needs to unstick a player by hand.
        player.then(Commands.literal("clear_branch").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");TimeBranch.cancel(p);BranchFist.clear(p);Erasure.forget(p);HexNetwork.sync(p);return 1;}));
        // Reports how much of the world is still owed a restore, and puts it all back on the spot. Both
        // are worth having by hand: the first to confirm nothing is stuck, the second to end the wait.
        root.then(Commands.literal("nothingness").then(Commands.literal("pending").executes(c->{
            ServerLevel level=c.getSource().getLevel();
            int count=Nothingness.pending(level);
            c.getSource().sendSuccess(()->Component.literal(count+" position(s) awaiting restoration in "+level.dimension().location()),false);
            return count;
        })).then(Commands.literal("restore").executes(c->{
            ServerLevel level=c.getSource().getLevel();
            int count=Nothingness.pending(level);
            Nothingness.restoreAll(level);
            c.getSource().sendSuccess(()->Component.literal("Restored "+count+" position(s)."),false);
            return count;
        })));
        player.then(Commands.literal("clear_quick").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");for(int i=0;i<HexData.QUICK_SLOTS;i++)HexData.quick(p,i,-1);HexNetwork.sync(p);return 1;}));
        player.then(Commands.literal("realm").then(Commands.literal("enter").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");if(!HexData.access(p)){c.getSource().sendFailure(Component.literal("That player does not have powers enabled."));return 0;}return PocketRealm.enter(p)?1:0;}))
            .then(Commands.literal("exit").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");return PocketRealm.leave(p)?1:0;})));
        player.then(Commands.literal("status").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");
            c.getSource().sendSuccess(()->Component.literal(p.getGameProfile().getName()+" — "+HexData.selected(p).title
                +" | energy "+Math.round(HexData.energy(p))+"/"+Math.round(HexData.maxEnergy(p))
                +" | ascended "+HexData.get(p).getBoolean("ascended")),false);return 1;}));
        // The Void Sea's hunter spends most of its life out of sight on purpose, which makes
        // "is it even there" a fair question. These answer it without breaking the illusion in play.
        var pilgrim=Commands.literal("pilgrim");
        pilgrim.then(Commands.literal("where").executes(c->{
            ServerLevel level=c.getSource().getLevel();
            var list=level.getEntitiesOfClass(com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity.class,
                new net.minecraft.world.phys.AABB(-3.0E7,-3.0E7,-3.0E7,3.0E7,3.0E7,3.0E7));
            var from=c.getSource().getPosition();
            if(list.isEmpty()){c.getSource().sendSuccess(()->Component.literal("Nothing is hunting this ocean."),false);return 0;}
            for(var q:list)c.getSource().sendSuccess(()->Component.literal(String.format(
                "%s \u00b7 %.0f %.0f %.0f \u00b7 %.0f blocks away \u00b7 lumen %d%%",
                q.state().name(),q.getX(),q.getY(),q.getZ(),Math.sqrt(q.distanceToSqr(from)),Math.round(q.glow()*100))),false);
            return list.size();}));
        pilgrim.then(Commands.literal("summon").executes(c->{
            ServerPlayer p=c.getSource().getPlayerOrException();ServerLevel level=p.serverLevel();
            var q=com.hexgodofstories.warping.leviathan.PilgrimWarden.ensure(level,com.hexgodofstories.warping.WarpMath.cellX(p.getX()));
            if(q==null){c.getSource().sendFailure(Component.literal("No hunter in this cell yet; try /hgos pilgrim spawn."));return 0;}
            double a=p.getRandom().nextDouble()*Math.PI*2;
            q.moveTo(p.getX()+Math.cos(a)*46,Math.max(com.hexgodofstories.warping.VoidSea.FLOOR+30,p.getY()-34),p.getZ()+Math.sin(a)*46,(float)(Math.toDegrees(a)),0f);
            if(q.ai()!=null)q.ai().alert(p);
            c.getSource().sendSuccess(()->Component.literal("It knows where you are."),true);return 1;}));
        pilgrim.then(Commands.literal("spawn").executes(c->{
            ServerPlayer p=c.getSource().getPlayerOrException();
            var q=com.hexgodofstories.warping.leviathan.PilgrimWarden.spawn(p.serverLevel(),com.hexgodofstories.warping.WarpMath.cellX(p.getX()),p.position());
            c.getSource().sendSuccess(()->Component.literal(q==null?"The abyss refused.":"The Abyssal Pilgrim has been given this ocean."),true);
            return q==null?0:1;}));
        pilgrim.then(Commands.literal("purge").executes(c->{
            var list=c.getSource().getLevel().getEntitiesOfClass(com.hexgodofstories.warping.leviathan.AbyssalPilgrimEntity.class,
                new net.minecraft.world.phys.AABB(-3.0E7,-3.0E7,-3.0E7,3.0E7,3.0E7,3.0E7));
            for(var q:list)q.beginDeath();
            int n=list.size();
            c.getSource().sendSuccess(()->Component.literal("Death sequence started for "+n+" leviathan(s)."),true);return n;}));
        root.then(pilgrim);
        root.then(player);e.getDispatcher().register(root);
    }
}
