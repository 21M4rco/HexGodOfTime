package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.network.HexNetwork;
import com.hexgodofstories.warping.MoonGravity;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

@Mod.EventBusSubscriber(modid=HexGodOfStories.ID,value=Dist.CLIENT)
public final class MoonClient {
    @SubscribeEvent public static void camera(ViewportEvent.ComputeCameraAngles event) {
        if(!MoonGravity.active(event.getCamera().getEntity()))return;
        // Forge's view is Z(roll) X(pitch) Y(yaw+180). Decompose the actual transported camera,
        // including the full roll, instead of rotating only the model or snapping at the equator.
        Quaternionf view=new Quaternionf().rotationY((float)Math.PI).mul(new Quaternionf(event.getCamera().rotation()).conjugate());
        Matrix3f m=new Matrix3f().set(view);
        double pitch=Math.asin(Math.max(-1,Math.min(1,m.m12())));
        event.setPitch((float)Math.toDegrees(pitch));
        event.setYaw((float)Math.toDegrees(Math.atan2(-m.m02(),m.m22()))-180);
        event.setRoll((float)Math.toDegrees(Math.atan2(-m.m10(),m.m11())));
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        var p=Minecraft.getInstance().player;
        if(event.phase!=TickEvent.Phase.END||!MoonGravity.active(p))return;
        // Orientation only: positions still use vanilla's validated movement packets.
        if(p.tickCount%2==0)HexNetwork.CHANNEL.sendToServer(new HexNetwork.MoonFrame(MoonGravity.forward(p)));
    }
    private MoonClient() {}
}
