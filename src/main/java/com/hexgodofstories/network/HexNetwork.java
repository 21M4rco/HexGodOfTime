package com.hexgodofstories.network;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.HexData;
import com.hexgodofstories.server.HexServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

public final class HexNetwork {
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(HexGodOfStories.id("main"),()->"6","6"::equals,"6"::equals);
    /** Highest accepted client action id; see {@link HexServer#input}. */
    public static final int MAX_ACTION=16;
    public static final int SYNC=0,ANIMATE=1,FX=2,FROZEN=3,GRIP=4,MEMORY=5,THREADS=6,SLOWED=7,ARCHITECTURE=8,BLEED=9,FIELD=10,DISGUISE=11,
        BRANCH=12,TORRENT=13,ERASURE=14,WARP=15,WARP_REALM=16,PILGRIM=17,PILGRIM_PATH=18,MOON_FRAME=19,WARP_PHASE=20,WARP_SHADOWS=21,WARP_EMERGE=22,CANDY_BODY=23,FROST=24;
    public record Input(int action,int value) {}
    /** A deliberate Fracture selection: a catalogue index and, where the mode needs one, a target. */
    public record Choice(int mode,java.util.UUID target) {}
    public record MoonFrame(net.minecraft.world.phys.Vec3 forward) {}
    public record Message(int kind,int entity,CompoundTag data) {}
    public static void init() {
        CHANNEL.messageBuilder(MoonFrame.class,3,NetworkDirection.PLAY_TO_SERVER)
            .encoder((m,b)->{b.writeFloat((float)m.forward.x);b.writeFloat((float)m.forward.y);b.writeFloat((float)m.forward.z);})
            .decoder(b->new MoonFrame(new net.minecraft.world.phys.Vec3(b.readFloat(),b.readFloat(),b.readFloat())))
            .consumerMainThread((m,c)->{
                ServerPlayer p=c.get().getSender();
                if(p!=null&&p.isAlive()&&com.hexgodofstories.warping.MoonGravity.active(p))
                    com.hexgodofstories.warping.MoonGravity.frame(p,m.forward);
                c.get().setPacketHandled(true);
            }).add();
        CHANNEL.messageBuilder(Input.class,0,NetworkDirection.PLAY_TO_SERVER)
            .encoder((m,b)->{b.writeVarInt(m.action);b.writeVarInt(m.value);})
            .decoder(b->new Input(b.readVarInt(),b.readVarInt()))
            .consumerMainThread((m,c)->{ServerPlayer p=c.get().getSender();if(p!=null&&p.isAlive()&&m.action>=0&&m.action<=MAX_ACTION)HexServer.input(p,m.action,m.value);c.get().setPacketHandled(true);}).add();
        CHANNEL.messageBuilder(Choice.class,2,NetworkDirection.PLAY_TO_SERVER)
            .encoder((m,b)->{b.writeVarInt(m.mode);b.writeBoolean(m.target!=null);if(m.target!=null)b.writeUUID(m.target);})
            .decoder(b->new Choice(b.readVarInt(),b.readBoolean()?b.readUUID():null))
            // The server resolves and validates every destination; an index and a name are all the
            // client is trusted with.
            .consumerMainThread((m,c)->{ServerPlayer p=c.get().getSender();if(p!=null&&p.isAlive()&&HexData.access(p))com.hexgodofstories.server.FractureTravel.choose(p,m.mode,m.target);c.get().setPacketHandled(true);}).add();
        CHANNEL.messageBuilder(Message.class,1,NetworkDirection.PLAY_TO_CLIENT)
            .encoder((m,b)->{b.writeVarInt(m.kind);b.writeVarInt(m.entity);b.writeNbt(m.data);})
            .decoder(b->new Message(b.readVarInt(),b.readVarInt(),b.readNbt()))
            .consumerMainThread((m,c)->{if(m.data!=null)DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->com.hexgodofstories.client.ClientState.receive(m));c.get().setPacketHandled(true);}).add();
    }
    /**
     * One leviathan presentation event, sent to everyone close enough to feel it. These are rare by
     * construction: the body itself is never networked, only breaches, impacts and screams.
     */
    public static void pilgrimEffect(Entity source,String name,net.minecraft.world.phys.Vec3 at,float power) {
        if(!(source.level() instanceof net.minecraft.server.level.ServerLevel level))return;
        CompoundTag d=new CompoundTag();d.putString("effect",name);d.putDouble("x",at.x);d.putDouble("y",at.y);d.putDouble("z",at.z);d.putFloat("power",power);
        near(level,at,160+power*120,new Message(PILGRIM,source.getId(),d));
    }
    public static void send(int action,int value) {CHANNEL.sendToServer(new Input(action,value));}
    public static void chooseFracture(int mode,java.util.UUID target) {CHANNEL.sendToServer(new Choice(mode,target));}
    public static void to(ServerPlayer p,Message m) {CHANNEL.send(PacketDistributor.PLAYER.with(()->p),m);}
    public static void tracking(Entity p,Message m) {CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(()->p),m);}
    /**
     * Everyone close enough to see it, whether or not they happen to be tracking the caster. A
     * sixty-block torrent reaches players the caster's own tracking range never would.
     */
    public static void near(net.minecraft.server.level.ServerLevel level,net.minecraft.world.phys.Vec3 at,double radius,Message m) {
        CHANNEL.send(PacketDistributor.NEAR.with(()->new PacketDistributor.TargetPoint(at.x,at.y,at.z,radius,level.dimension())),m);
    }
    public static void sync(ServerPlayer p) {
        // Locking the mod must hide it, not erase the player's saved loadout.
        if(HexData.access(p))HexData.refreshQuick(p);
        tracking(p,new Message(SYNC,p.getId(),light(HexData.get(p))));
    }
    /**
     * The routine state packet goes out once a second to everyone tracking the player, so a mimicked
     * entity's full snapshot — which can run to kilobytes — never rides along with it. Only the small
     * descriptor travels here; {@link com.hexgodofstories.server.Masquerade} sends the body once, on change.
     */
    /** The same trimmed state, addressed to one viewer that has just started tracking the player. */
    public static void syncTo(ServerPlayer viewer,ServerPlayer subject) {
        to(viewer,new Message(SYNC,subject.getId(),light(HexData.get(subject))));
    }
    private static CompoundTag light(CompoundTag data) {
        CompoundTag copy=data.copy();
        if(copy.contains("disguise"))copy.getCompound("disguise").remove("nbt");
        return copy;
    }
    public static void animate(ServerPlayer p,String name) {CompoundTag d=new CompoundTag();d.putString("animation",name);tracking(p,new Message(ANIMATE,p.getId(),d));}
    /** Arrival has no RiftEntity. Nearby viewers receive the nebula even before tracking starts. */
    public static void arrival(Entity entity) {
        if(!(entity.level() instanceof net.minecraft.server.level.ServerLevel level))return;
        CompoundTag d=new CompoundTag();d.putString("effect","nebula_arrival");
        d.putDouble("x",entity.getX());d.putDouble("y",entity.getY());d.putDouble("z",entity.getZ());
        near(level,entity.position(),64,new Message(FX,entity.getId(),d));
    }
    public static void fx(Entity p,String name) {fx(p,name,p.getX(),p.getY(),p.getZ());}
    /** Snapshot the discharged ray on the server so every viewer sees the same blue frost cloud. */
    public static void frostBurst(ServerPlayer caster,net.minecraft.world.phys.Vec3 origin,
                                  net.minecraft.world.phys.Vec3 direction,double reach) {
        CompoundTag d=new CompoundTag();d.putString("effect","frost_burst");
        d.putDouble("x",origin.x);d.putDouble("y",origin.y);d.putDouble("z",origin.z);
        d.putDouble("dx",direction.x);d.putDouble("dy",direction.y);d.putDouble("dz",direction.z);
        d.putDouble("reach",reach);
        near(caster.serverLevel(),origin,64,new Message(FX,caster.getId(),d));
    }
    public static void fx(Entity p,String name,double x,double y,double z) {
        CompoundTag d=new CompoundTag();d.putString("effect",name);d.putDouble("x",x);d.putDouble("y",y);d.putDouble("z",z);tracking(p,new Message(FX,p.getId(),d));
    }
}
