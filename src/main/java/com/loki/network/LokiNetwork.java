package com.loki.network;

import com.loki.Loki;
import com.loki.data.LokiData;
import com.loki.server.LokiServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

public final class LokiNetwork {
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(Loki.id("main"),()->"1","1"::equals,"1"::equals);
    public record Input(int action,int value) {}
    public record Message(int kind,int entity,CompoundTag data) {}
    public static void init() {
        CHANNEL.messageBuilder(Input.class,0,NetworkDirection.PLAY_TO_SERVER)
            .encoder((m,b)->{b.writeVarInt(m.action);b.writeVarInt(m.value);})
            .decoder(b->new Input(b.readVarInt(),b.readVarInt()))
            .consumerMainThread((m,c)->{ServerPlayer p=c.get().getSender();if(p!=null&&p.isAlive()&&m.action>=0&&m.action<=6)LokiServer.input(p,m.action,m.value);c.get().setPacketHandled(true);}).add();
        CHANNEL.messageBuilder(Message.class,1,NetworkDirection.PLAY_TO_CLIENT)
            .encoder((m,b)->{b.writeVarInt(m.kind);b.writeVarInt(m.entity);b.writeNbt(m.data);})
            .decoder(b->new Message(b.readVarInt(),b.readVarInt(),b.readNbt()))
            .consumerMainThread((m,c)->{if(m.data!=null)DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->com.loki.client.ClientState.receive(m));c.get().setPacketHandled(true);}).add();
    }
    public static void send(int action,int value) {CHANNEL.sendToServer(new Input(action,value));}
    public static void to(ServerPlayer p,Message m) {CHANNEL.send(PacketDistributor.PLAYER.with(()->p),m);}
    public static void tracking(Entity p,Message m) {CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(()->p),m);}
    public static void sync(ServerPlayer p) {tracking(p,new Message(0,p.getId(),LokiData.get(p).copy()));}
    public static void animate(ServerPlayer p,String name) {CompoundTag d=new CompoundTag();d.putString("animation",name);tracking(p,new Message(1,p.getId(),d));}
    public static void fx(Entity p,String name) {CompoundTag d=new CompoundTag();d.putString("effect",name);d.putDouble("x",p.getX());d.putDouble("y",p.getY());d.putDouble("z",p.getZ());tracking(p,new Message(2,p.getId(),d));}
}
