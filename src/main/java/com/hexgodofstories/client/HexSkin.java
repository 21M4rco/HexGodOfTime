package com.hexgodofstories.client;




import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import org.lwjgl.opengl.GL11;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(
   modid = "hexgodofstories",
   value = {Dist.CLIENT}
)
public final class HexSkin {
   private static final int MAX_ENTRIES = 64;
   private static final long MAX_BYTES = 33554432L;
   private static final Map<UUID, HexSkin.Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
   private static Object world;
   private static long bytes;
   private static int ticks;
   private static boolean warned;
   private static final Map<UUID, Long> RETRY = new LinkedHashMap<>();

   private HexSkin() {
   }

   public static float progress(AbstractClientPlayer var0) {
      return ClientState.progress(var0.getId(), Minecraft.getInstance().getFrameTime());
   }

   public static ResourceLocation resolve(AbstractClientPlayer var0, ResourceLocation var1) {
      if (!RenderSystem.isOnRenderThread()) {
         return var1;
      } else {
         Minecraft var2 = Minecraft.getInstance();
         if (var2.level == null) {
            return var1;
         } else {
            if (world != var2.level) {
               clear();
               world = var2.level;
            }

            float var3 = progress(var0);
            boolean candySkin=CandyCorruptionClient.skinActive(var0.getId());
            if (var3 <= 0.0F && !candySkin) {
               return var1;
            } else {
               UUID var4 = var0.getUUID();
               long var5 = System.nanoTime();
               Long var7 = RETRY.get(var4);
               if (var7 != null && var5 < var7) {
                  return var1;
               } else {
                  TextureManager var8 = var2.getTextureManager();
                  AbstractTexture var9 = var8.getTexture(var1);
                  boolean var10 = "slim".equals(var0.getModelName());
                  HexSkin.Entry var11 = CACHE.get(var4);
                  int var12 = var9.getId();
                  if (var11 != null && (!var11.original.equals(var1) || var11.source != var9 || var11.sourceId != var12 || var11.slim != var10)) {
                     CACHE.remove(var4);
                     dispose(var11);
                     var11 = null;
                  }

                  boolean var14 = false;
                  boolean var15 = false;
                  int var16 = Math.min(96, Math.round(var3 * 96.0F));
                  int var17 = var16 | (var14 ? 128 : 0) | (var15 ? 256 : 0) | (CandyCorruptionClient.skinState(var0.getId()) << 10);
                  if (var11 != null && var11.state == var17) {
                     var11.lastUse = var5;
                     return var11.location;
                  } else {
                     int var18 = GL11.glGetInteger(32873);

                     try {
                        if (var11 == null) {
                           var11 = create(var4, var1, var9, var10, var8);
                           if (var11 == null) {
                              if (RETRY.size() >= 64) {
                                 RETRY.remove(RETRY.keySet().iterator().next());
                              }

                              RETRY.put(var4, var5 + 1000000000L);
                              return var1;
                           }

                           CACHE.put(var4, var11);
                           bytes = bytes + var11.bytes;
                           evict();
                        }

                        var11.lastUse = var5;
                        if (var11.state != var17) {
                           compose(var11, (float)var16 / 96.0F, var14, var15, var0.getId());
                           var11.texture.upload();
                           var11.state = var17;
                        }

                        RETRY.remove(var4);
                        return var11.location;
                     } catch (RuntimeException var25) {
                        HexSkin.Entry var20 = CACHE.remove(var4);
                        if (var20 != null) {
                           dispose(var20);
                        }

                        if (RETRY.size() >= 64) {
                           RETRY.remove(RETRY.keySet().iterator().next());
                        }

                        RETRY.put(var4, var5 + 5000000000L);
                        if (!warned) {
                           warned = true;
                           LoggerFactory.getLogger("HexGodOfStories/Outfit").warn("Could not compose temporal outfit; keeping the original skin", var25);
                        }

                        return var1;
                     } finally {
                        GlStateManager._bindTexture(var18);
                     }
                  }
               }
            }
         }
      }
   }

   private static HexSkin.Entry create(UUID var0, ResourceLocation var1, AbstractTexture var2, boolean var3, TextureManager var4) {
      GlStateManager._bindTexture(var2.getId());
      int var5 = GlStateManager._getTexLevelParameter(3553, 0, 4096);
      int var6 = GlStateManager._getTexLevelParameter(3553, 0, 4097);
      if (var5 >= 64 && var5 <= 1024 && var5 == var6 && var5 % 64 == 0) {
         NativeImage var7 = new NativeImage(var5, var6, false);
         NativeImage var8 = null;
         DynamicTexture var9 = null;

         try {
            var7.downloadTexture(0, false);
            var8 = new NativeImage(var5, var6, false);
            var8.copyFrom(var7);
            var9 = new DynamicTexture(var8);
            ResourceLocation var10 = new ResourceLocation("hexgodofstories", "dynamic/temporal_outfit/" + var0);
            var4.register(var10, var9);
            return new HexSkin.Entry(var1, var2, var3, var7, var9, var10);
         } catch (RuntimeException var111) {
            var7.close();
            if (var9 != null) {
               var9.close();
            } else if (var8 != null) {
               var8.close();
            }

            throw var111;
         }
      } else {
         return null;
      }
   }

   private static void compose(HexSkin.Entry var0, float var1, boolean var2, boolean var3, int entityId) {
      NativeImage var4 = var0.texture.getPixels();
      OutfitPattern var5 = var0.slim ? OutfitPattern.SLIM : OutfitPattern.CLASSIC;
      int var6 = var0.base.getWidth();

      for (int var7 = 0; var7 < var6; var7++) {
         for (int var8 = 0; var8 < var6; var8++) {
            int sx=var8 * 64 / var6,sy=var7 * 64 / var6;
            int dressed=var5.pixel(var0.base.getPixelRGBA(var8,var7),sx,sy,var1,var2,var3);
            var4.setPixelRGBA(var8,var7,CandyCorruptionClient.tint(entityId,dressed,sx,sy));
         }
      }
   }

   private static void evict() {
      Iterator var0 = CACHE.values().iterator();

      while ((CACHE.size() > 64 || bytes > 33554432L) && var0.hasNext()) {
         HexSkin.Entry var1 = (HexSkin.Entry)var0.next();
         var0.remove();
         dispose(var1);
      }
   }

   private static void dispose(HexSkin.Entry var0) {
      var0.base.close();
      Minecraft.getInstance().getTextureManager().release(var0.location);
      bytes = bytes - var0.bytes;
   }

   public static void clear() {
      for (HexSkin.Entry var1 : CACHE.values()) {
         dispose(var1);
      }

      CACHE.clear();
      RETRY.clear();
      bytes = 0L;
   }

   @SubscribeEvent
   public static void tick(ClientTickEvent var0) {
      if (var0.phase == Phase.END) {
         Minecraft var1 = Minecraft.getInstance();
         if (world != var1.level) {
            clear();
            world = var1.level;
         }

         if (++ticks % 100 == 0) {
            long var2 = System.nanoTime() - 15000000000L;
            Iterator var4 = CACHE.values().iterator();

            while (var4.hasNext()) {
               HexSkin.Entry var5 = (HexSkin.Entry)var4.next();
               if (var5.lastUse < var2) {
                  var4.remove();
                  dispose(var5);
               }
            }
         }
      }
   }

   private static final class Entry {
      final ResourceLocation original;
      final ResourceLocation location;
      final AbstractTexture source;
      final int sourceId;
      final boolean slim;
      final NativeImage base;
      final DynamicTexture texture;
      final long bytes;
      long lastUse;
      int state = -1;

      Entry(ResourceLocation var1, AbstractTexture var2, boolean var3, NativeImage var4, DynamicTexture var5, ResourceLocation var6) {
         this.original = var1;
         this.source = var2;
         this.sourceId = var2.getId();
         this.slim = var3;
         this.base = var4;
         this.texture = var5;
         this.location = var6;
         this.bytes = (long)var4.getWidth() * (long)var4.getHeight() * 8L;
      }
   }

   @EventBusSubscriber(
      modid = "hexgodofstories",
      value = {Dist.CLIENT},
      bus = Bus.MOD
   )
   public static final class Reload {
      @SubscribeEvent
      public static void register(RegisterClientReloadListenersEvent var0) {
         var0.registerReloadListener((ResourceManagerReloadListener)var0x -> HexSkin.clear());
      }
   }
}

