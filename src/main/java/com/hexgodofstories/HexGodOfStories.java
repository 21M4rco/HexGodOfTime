package com.hexgodofstories;

import com.hexgodofstories.entity.*;
import com.hexgodofstories.network.HexNetwork;
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

@Mod(HexGodOfStories.ID)
public final class HexGodOfStories {
    public static final String ID = "hexgodofstories";
    public static ResourceLocation id(String path) { return new ResourceLocation(ID, path); }
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, ID);
    public static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLES = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, ID);

    public static final RegistryObject<EntityType<IllusionEntity>> ILLUSION = ENTITIES.register("projection", () -> EntityType.Builder.of(IllusionEntity::new, MobCategory.MISC).sized(.6f, 1.8f).clientTrackingRange(10).updateInterval(2).build("hexgodofstories:projection"));
    public static final RegistryObject<EntityType<SpellProjectile>> PROJECTILE = ENTITIES.register("spell", () -> EntityType.Builder.<SpellProjectile>of(SpellProjectile::new, MobCategory.MISC).sized(.18f,.18f).clientTrackingRange(10).updateInterval(1).build("hexgodofstories:spell"));
    public static final RegistryObject<EntityType<ThrownDagger>> THROWN_DAGGER = ENTITIES.register("thrown_dagger", () -> EntityType.Builder.<ThrownDagger>of(ThrownDagger::new, MobCategory.MISC).sized(.28f,.28f).clientTrackingRange(8).updateInterval(1).build("hexgodofstories:thrown_dagger"));
    public static final RegistryObject<EntityType<RiftEntity>> RIFT = ENTITIES.register("rift", () -> EntityType.Builder.<RiftEntity>of(RiftEntity::new, MobCategory.MISC).sized(2.2f,2.8f).clientTrackingRange(10).updateInterval(2).fireImmune().noSummon().build("hexgodofstories:rift"));

    public static final RegistryObject<EntityType<StarfallEntity>> STARFALL = ENTITIES.register("starfall", () -> EntityType.Builder.<StarfallEntity>of(StarfallEntity::new, MobCategory.MISC).sized(.6f,.6f).clientTrackingRange(12).updateInterval(1).fireImmune().noSummon().build("hexgodofstories:starfall"));

    public static final RegistryObject<EntityType<ThroneSeat>> THRONE_SEAT = ENTITIES.register("throne_seat", () -> EntityType.Builder.<ThroneSeat>of(ThroneSeat::new, MobCategory.MISC).sized(.5f,.2f).clientTrackingRange(8).noSave().noSummon().build("hexgodofstories:throne_seat"));

    /**
     * The absence a Time Branch torrent leaves behind. Registered with no {@code BlockItem} on purpose:
     * with no item form, no loot table and bedrock's hardness it cannot be mined, blown up, picked or
     * obtained, so it can only ever exist where the ability put it and only until the world is restored.
     */
    public static final RegistryObject<net.minecraft.world.level.block.Block> NOTHINGNESS =
        BLOCKS.register("nothingness", com.hexgodofstories.block.NothingnessBlock::new);

    public static final RegistryObject<Item> DAGGER = ITEMS.register("dagger", () -> new ConjuredWeapon(0));
    public static final RegistryObject<Item> LAEVATEINN = ITEMS.register("laevateinn", () -> new ConjuredWeapon(1));
    public static final RegistryObject<Item> TIME_STICK = ITEMS.register("time_stick", () -> new ConjuredWeapon(2));

    public static final RegistryObject<SoundEvent> SORCERY = sound("sorcery"), ILLUSION_SOUND = sound("illusion"), TELEPORT = sound("teleport"),
        SLIP = sound("time_slip"), STOP = sound("time_stop"), RESUME = sound("time_resume"), ASCEND = sound("ascend"), CONJURE = sound("conjure"),
        BLADE_SWING = sound("blade_swing"), BLADE_HIT = sound("blade_hit"), BLADE_THROW = sound("blade_throw"), BLADE_EMBED = sound("blade_embed"),
        RIFT_OPEN = sound("rift_open"), RIFT_CLOSE = sound("rift_close");
    /**
     * Time Branch Unleashing's own bed. Each of these is one layer: the charge stacks the first five and
     * drives their volume and pitch from the synchronised charge, so the build is assembled at the ear
     * rather than streamed from the server, and none of it is an explosion or a thunderclap.
     */
    public static final RegistryObject<SoundEvent> BRANCH_HUM = sound("branch_hum"), BRANCH_RESONANCE = sound("branch_resonance"),
        BRANCH_SHIMMER = sound("branch_shimmer"), BRANCH_PRESSURE = sound("branch_pressure"), BRANCH_CRACKLE = sound("branch_crackle"),
        BRANCH_READY = sound("branch_ready"), BRANCH_OPEN = sound("branch_open"), BRANCH_RELEASE = sound("branch_release"),
        BRANCH_ROAR = sound("branch_roar"), BRANCH_ERASE = sound("branch_erase");
    /** A stone burning its way through an atmosphere, and what it does when it arrives. */
    public static final RegistryObject<SoundEvent> METEOR_BURN = sound("meteor_burn"), METEOR_ROAR = sound("meteor_roar"),
        METEOR_IMPACT = sound("meteor_impact");
    public static final RegistryObject<SoundEvent> GRIP_HOLD = sound("grip_hold"), EMERALD_CAST = sound("emerald_cast");
    private static RegistryObject<SoundEvent> sound(String name) { return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id(name))); }

    /**
     * Option-free particle types: the behaviour lives in each client factory, so nothing has to travel
     * on the wire. Vanilla keeps SimpleParticleType's constructor protected, hence the subclass.
     */
    public static final class Glow extends net.minecraft.core.particles.SimpleParticleType {
        public Glow() { super(false); }
    }
    public static final RegistryObject<Glow> EMBER = particle("ember");
    public static final RegistryObject<Glow> GOLD_EMBER = particle("gold_ember");
    public static final RegistryObject<Glow> RUNE = particle("rune");
    public static final RegistryObject<Glow> SHARD = particle("shard");
    public static final RegistryObject<Glow> MOTE = particle("mote");
    public static final RegistryObject<Glow> BLOOD = particle("blood");
    /** The nebula family: the soft, layered look the flight cloud established, reusable per ability. */
    public static final RegistryObject<Glow> NEBULA = particle("nebula");
    public static final RegistryObject<Glow> VEIL = particle("veil");
    public static final RegistryObject<Glow> STAR = particle("star");
    public static final RegistryObject<Glow> SMOKE = particle("smoke");
    /** What is left of a body or a block that has been taken out of the timeline. */
    public static final RegistryObject<Glow> TEMPORAL_DUST = particle("temporal_dust");
    public static final RegistryObject<Glow> BRANCH_THREAD = particle("branch_thread");
    public static final RegistryObject<Glow> SPECTRAL = particle("spectral");
    /** Atmospheric entry: flame torn off the stone, cooling cinders and the smoke behind it. */
    public static final RegistryObject<Glow> METEOR_FIRE = particle("meteor_fire");
    public static final RegistryObject<Glow> CINDER = particle("cinder");
    public static final RegistryObject<Glow> ASH = particle("ash");
    private static RegistryObject<Glow> particle(String name) { return PARTICLES.register(name, Glow::new); }

    static {
        TABS.register("purpose", () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.hexgodofstories")).icon(() -> new ItemStack(DAGGER.get())).displayItems((p,o) -> {o.accept(DAGGER.get());o.accept(LAEVATEINN.get());o.accept(TIME_STICK.get());}).build());
    }
    public HexGodOfStories() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus); ITEMS.register(bus); BLOCKS.register(bus); SOUNDS.register(bus); TABS.register(bus); PARTICLES.register(bus);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(ILLUSION.get(), IllusionEntity.attributes().build()));
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) -> e.enqueueWork(HexNetwork::init));
    }
}
