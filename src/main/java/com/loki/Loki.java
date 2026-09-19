package com.loki;

import com.loki.entity.*;
import com.loki.network.LokiNetwork;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;

@Mod(Loki.ID)
public final class Loki {
    public static final String ID = "loki";
    public static ResourceLocation id(String path) { return new ResourceLocation(ID, path); }
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, ID);
    public static final RegistryObject<EntityType<IllusionEntity>> ILLUSION = ENTITIES.register("projection", () -> EntityType.Builder.of(IllusionEntity::new, MobCategory.MISC).sized(.6f, 1.8f).clientTrackingRange(10).updateInterval(2).build("loki:projection"));
    public static final RegistryObject<EntityType<SpellProjectile>> PROJECTILE = ENTITIES.register("spell", () -> EntityType.Builder.<SpellProjectile>of(SpellProjectile::new, MobCategory.MISC).sized(.18f,.18f).clientTrackingRange(10).updateInterval(1).build("loki:spell"));
    public static final RegistryObject<Item> DAGGER = ITEMS.register("dagger", () -> new ConjuredWeapon(0));
    public static final RegistryObject<Item> LAEVATEINN = ITEMS.register("laevateinn", () -> new ConjuredWeapon(1));
    public static final RegistryObject<Item> TIME_STICK = ITEMS.register("time_stick", () -> new ConjuredWeapon(2));
    public static final RegistryObject<SoundEvent> SORCERY = sound("sorcery"), ILLUSION_SOUND = sound("illusion"), TELEPORT = sound("teleport"), SLIP = sound("time_slip"), STOP = sound("time_stop"), RESUME = sound("time_resume"), ASCEND = sound("ascend"), CONJURE = sound("conjure");
    private static RegistryObject<SoundEvent> sound(String name) { return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id(name))); }
    static {
        TABS.register("purpose", () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.loki")).icon(() -> new ItemStack(DAGGER.get())).displayItems((p,o) -> {o.accept(DAGGER.get());o.accept(LAEVATEINN.get());o.accept(TIME_STICK.get());}).build());
    }
    public Loki() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus); ITEMS.register(bus); SOUNDS.register(bus); TABS.register(bus);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(ILLUSION.get(), IllusionEntity.attributes().build()));
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) -> e.enqueueWork(LokiNetwork::init));
    }
}
