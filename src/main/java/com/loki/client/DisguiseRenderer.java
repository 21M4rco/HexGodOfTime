package com.loki.client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.client.event.RenderPlayerEvent;
import java.util.*;

public final class DisguiseRenderer {
    private static boolean rendering;
    private static final Map<UUID,LivingEntity> CACHE=new HashMap<>();
    public static void render(RenderPlayerEvent.Pre event) {
        if(rendering)return;var p=event.getEntity();var d=ClientState.data(p.getId()).getCompound("disguise");if(d.isEmpty()||d.getLong("end")<ClientState.now()||ClientState.now()-d.getLong("start")<8)return;
        var mc=Minecraft.getInstance();if(mc.level==null)return;UUID id=d.getUUID("uuid");
        LivingEntity model=CACHE.get(id);if(model==null||model.level()!=mc.level) {
            if("minecraft:player".equals(d.getString("type"))){var info=mc.getConnection()==null?null:mc.getConnection().getPlayerInfo(id);if(info==null)return;model=new RemotePlayer(mc.level,info.getProfile());}
            else {Entity e=BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(d.getString("type"))).create(mc.level);if(!(e instanceof LivingEntity l))return;model=l;}
            if(CACHE.size()>32)CACHE.clear();CACHE.put(id,model);
        }
        model.tickCount=p.tickCount;model.yBodyRot=p.yBodyRot;model.yBodyRotO=p.yBodyRotO;model.yHeadRot=p.yHeadRot;model.yHeadRotO=p.yHeadRotO;model.setXRot(p.getXRot());model.xRotO=p.xRotO;model.setYRot(p.getYRot());model.setPose(p.getPose());model.setPos(p.position());model.walkAnimation.update(p.walkAnimation.speed(),1);model.setDeltaMovement(p.getDeltaMovement());
        event.setCanceled(true);rendering=true;
        try{mc.getEntityRenderDispatcher().getRenderer(model).render(model,p.getYRot(),event.getPartialTick(),event.getPoseStack(),event.getMultiBufferSource(),event.getPackedLight());}finally{rendering=false;}
    }
}
