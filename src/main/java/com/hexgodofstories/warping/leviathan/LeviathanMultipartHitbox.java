package com.hexgodofstories.warping.leviathan;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.entity.PartEntity;

/**
 * One collision volume belonging to a visible section of the body. Sections are grouped so damage
 * and contact can be attributed to what the player actually saw touch them: jaws to the head, a
 * whip strike to the tail, a body slam to the mid spine. Everything routes back to the parent.
 */
public final class LeviathanMultipartHitbox extends PartEntity<AbyssalPilgrimEntity> {
    public enum Section { HEAD, NECK, BODY, TAIL }

    private final AbyssalPilgrimEntity parent;
    private final EntityDimensions size;
    private final Section section;
    private final int index;

    public LeviathanMultipartHitbox(AbyssalPilgrimEntity parent, Section section, int index, float width, float height) {
        super(parent);
        this.parent = parent;
        this.section = section;
        this.index = index;
        this.size = EntityDimensions.scalable(width, height);
        refreshDimensions();
    }

    public Section section() { return section; }
    public int index() { return index; }
    public AbyssalPilgrimEntity leviathan() { return parent; }

    // A hitbox carries no state of its own: the parent owns everything and repositions it each tick.
    @Override protected void defineSynchedData() { }
    @Override protected void readAdditionalSaveData(CompoundTag tag) { }
    @Override protected void addAdditionalSaveData(CompoundTag tag) { }

    @Override public EntityDimensions getDimensions(Pose pose) { return size; }

    /** Every blow is forwarded to the parent, which refuses all of them. */
    @Override public boolean hurt(DamageSource source, float amount) { return parent.hurt(source, amount); }

    @Override public boolean isPickable() { return parent.isAlive(); }

    @Override public boolean is(Entity other) { return this == other || parent == other; }

    @Override public boolean isPushable() { return false; }

    @Override public void push(double x, double y, double z) { }

    @Override public boolean canBeCollidedWith() { return false; }
}
