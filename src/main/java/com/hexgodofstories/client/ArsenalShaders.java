package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The Crown of Barrels' one shader (shaders/core/arsenal_gun): vanilla's entity lighting, plus the burning edge a
 * gun forms and comes apart behind, the glow along its silhouette and the flare of its own muzzle flash.
 *
 * <p>A driver that will not compile it costs only the effect: the guns are then drawn with vanilla's entity shader
 * and simply appear, and the log says why, once, in words the startup check looks for.
 */
@Mod.EventBusSubscriber(modid = HexGodOfStories.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ArsenalShaders {
    private ArsenalShaders() { }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static ShaderInstance gun;

    @SubscribeEvent public static void register(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), HexGodOfStories.id("arsenal_gun"), DefaultVertexFormat.NEW_ENTITY), s -> gun = s);
        } catch (Exception failed) {
            gun = null;
            LOGGER.error("Crown of Barrels shader failed to load; its guns will be drawn plain", failed);
        }
    }

    /** The shader, or null when it could not be loaded. */
    static ShaderInstance gun() {return gun;}
}
