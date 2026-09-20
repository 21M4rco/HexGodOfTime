package com.loki.server;
import com.loki.Loki;
import com.loki.data.*;
import com.loki.network.LokiNetwork;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=Loki.ID)
public final class LokiCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent e) {
        var root=Commands.literal("loki").requires(s->s.hasPermission(2));
        var player=Commands.argument("player",EntityArgument.player());
        for(String verb:new String[]{"set","add"}) {
            var node=Commands.literal(verb);
            for(Discipline d:Discipline.values())node.then(Commands.literal(d.name().toLowerCase()).then(Commands.argument("level",IntegerArgumentType.integer(0,1000)).executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");int n=IntegerArgumentType.getInteger(c,"level");LokiData.mastery(p,d,verb.equals("add")?LokiData.mastery(p,d)+n:n);LokiNetwork.sync(p);return 1;})));
            player.then(node);
        }
        var unlock=Commands.literal("unlock");
        unlock.then(Commands.literal("all").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");for(Discipline d:Discipline.values())LokiData.mastery(p,d,1000);for(Ability a:Ability.values())LokiData.get(p).putBoolean("unlock_"+a.name(),true);LokiData.energy(p,LokiData.maxEnergy(p));LokiNetwork.sync(p);return 1;}));
        for(Ability a:Ability.values())unlock.then(Commands.literal(a.name().toLowerCase()).executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");LokiData.get(p).putBoolean("unlock_"+a.name(),true);LokiNetwork.sync(p);return 1;}));
        player.then(unlock);
        player.then(Commands.literal("energy").then(Commands.argument("amount",FloatArgumentType.floatArg(0,1000)).executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");LokiData.energy(p,FloatArgumentType.getFloat(c,"amount"));LokiNetwork.sync(p);return 1;})));
        player.then(Commands.literal("transform").then(Commands.argument("active",BoolArgumentType.bool()).executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");LokiData.get(p).putBoolean("ascended",BoolArgumentType.getBool(c,"active"));LokiData.get(p).putLong("transformStart",LokiData.now(p));LokiNetwork.fx(p,"ascend");LokiNetwork.sync(p);return 1;})));
        player.then(Commands.literal("reset").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");LokiServer.clear(p,true);p.getPersistentData().remove("Loki");LokiNetwork.sync(p);return 1;}));
        player.then(Commands.literal("clear_illusions").executes(c->{LokiServer.clearIllusions(EntityArgument.getPlayer(c,"player"));return 1;}));
        player.then(Commands.literal("clear_time").executes(c->{TemporalEngine.clear(EntityArgument.getPlayer(c,"player"));return 1;}));
        player.then(Commands.literal("clear_bleed").executes(c->{Bleed.clear(EntityArgument.getPlayer(c,"player"));return 1;}));
        player.then(Commands.literal("clear_quick").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");for(int i=0;i<LokiData.QUICK_SLOTS;i++)LokiData.quick(p,i,-1);LokiNetwork.sync(p);return 1;}));
        player.then(Commands.literal("realm").then(Commands.literal("enter").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");return PocketRealm.enter(p)?1:0;}))
            .then(Commands.literal("exit").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");return PocketRealm.leave(p)?1:0;})));
        player.then(Commands.literal("status").executes(c->{ServerPlayer p=EntityArgument.getPlayer(c,"player");
            c.getSource().sendSuccess(()->Component.literal(p.getGameProfile().getName()+" — "+LokiData.selected(p).title
                +" | energy "+Math.round(LokiData.energy(p))+"/"+Math.round(LokiData.maxEnergy(p))
                +" | ascended "+LokiData.get(p).getBoolean("ascended")),false);return 1;}));
        root.then(player);e.getDispatcher().register(root);
    }
}
