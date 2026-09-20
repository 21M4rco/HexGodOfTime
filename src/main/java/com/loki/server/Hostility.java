package com.loki.server;

import com.loki.entity.IllusionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import java.util.Objects;

/**
 * What a projection is allowed to fight. The test is written against interfaces rather than a list of
 * entity ids, so a hostile creature from any mod is recognised the moment it implements {@link Enemy}
 * or turns angry, while somebody's wolf, a villager or a cow stays off the list unless it has actually
 * drawn blood.
 */
public final class Hostility {
    private Hostility() {}

    /** True when a decoy belonging to {@code owner} should be willing to attack {@code candidate}. */
    public static boolean hostile(LivingEntity candidate,ServerPlayer owner) {
        if(candidate==null||owner==null||candidate==owner)return false;
        if(!candidate.isAlive()||candidate.isSpectator()||candidate.isRemoved())return false;
        if(candidate instanceof IllusionEntity decoy&&Objects.equals(decoy.owner(),owner.getUUID()))return false;
        if(owner.isAlliedTo(candidate)||candidate.isAlliedTo(owner))return false;
        if(candidate instanceof TamableAnimal tame&&tame.isTame()&&owner.getUUID().equals(tame.getOwnerUUID()))return false;
        if(candidate instanceof Player other&&!LokiServer.validTarget(owner,other))return false;

        long now=owner.level().getGameTime();
        // Anything that has struck the keeper is fair game, whatever it normally is.
        if(Threat.harmedBy(owner.getUUID(),candidate,now))return true;
        if(candidate instanceof Mob mob&&aimedAt(mob,owner))return true;
        // Players are never attacked on sight; only the grudge above brings them in.
        if(candidate instanceof Player)return false;
        if(candidate instanceof Enemy)return true;
        if(candidate instanceof NeutralMob neutral&&neutral.isAngryAt(owner))return true;
        return false;
    }

    /** A creature already hunting the keeper — or one of the keeper's own copies — is hostile by definition. */
    private static boolean aimedAt(Mob mob,ServerPlayer owner) {
        LivingEntity target=mob.getTarget();
        if(target==null)return false;
        if(target==owner)return true;
        return target instanceof IllusionEntity decoy&&Objects.equals(decoy.owner(),owner.getUUID());
    }
}
