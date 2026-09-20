package com.loki.server;

import com.loki.data.*;
import com.loki.network.LokiNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDamageEvent;

/** One empty right-hand contact, validated through the actual server melee damage call. */
public final class BranchFist {
    private BranchFist() {}
    private record Contact(ServerPlayer player,Entity target,DamageSource source) {}
    private static final ThreadLocal<Contact> CONTACT=new ThreadLocal<>();

    public static boolean charged(Player p) {return LokiData.get(p).getLong(BranchFistState.UNTIL)>LokiData.now(p);}
    private static boolean eligible(ServerPlayer p) {
        return p.isAlive()&&!p.isSpectator()&&Transformation.transformed(p)
            &&!TemporalEngine.frozen(p)&&!Erasure.erasing(p)&&!TimeBranch.charging(p);
    }
    public static void arm(ServerPlayer p) {
        if(LokiData.selected(p)!=Ability.TIME_BRANCH||!LokiData.unlocked(p,Ability.TIME_BRANCH))return;
        if(!eligible(p)){notice(p,"The Time Branches answer only the transformed and free.");return;}
        var d=LokiData.get(p);long now=LokiData.now(p);
        if(charged(p))return;
        if(d.getLong(BranchFistState.COOLDOWN)>now){notice(p,"The charged fist is recovering.");return;}
        if(!LokiData.spend(p,BranchFistState.COST)){notice(p,"Not enough Temporal Energy.");return;}
        d.putLong(BranchFistState.START,now);d.putLong(BranchFistState.UNTIL,now+BranchFistState.WINDOW);
        d.putLong(BranchFistState.COOLDOWN,now+BranchFistState.RECOVERY);
        d.remove(BranchFistState.IMPACT);
        LokiNetwork.sync(p);
    }
    public static void tick(ServerPlayer p) {
        var d=LokiData.get(p);
        if(d.contains(BranchFistState.UNTIL)&&(!charged(p)||!eligible(p)))clear(p);
    }
    public static void clear(ServerPlayer p) {
        var d=LokiData.get(p);
        if(!d.contains(BranchFistState.UNTIL))return;
        d.remove(BranchFistState.UNTIL);d.remove(BranchFistState.START);
        LokiNetwork.sync(p);
    }

    /** Scoped around only Player.attack's primary hurt call, never sweep, projectiles or other spells. */
    public static boolean melee(Player player,Entity target,DamageSource source,float amount) {
        if(!(player instanceof ServerPlayer p)||!charged(p)||!eligible(p)
            ||p.getMainArm()!=HumanoidArm.RIGHT||!p.getMainHandItem().isEmpty())return target.hurt(source,amount);
        Contact previous=CONTACT.get();
        CONTACT.set(new Contact(p,target,source));
        try {return target.hurt(source,amount);}
        finally {if(previous==null)CONTACT.remove();else CONTACT.set(previous);}
    }

    /** After shields, invulnerability, cancelled attack/hurt events and damage reductions. */
    public static void damage(LivingDamageEvent event) {
        Contact c=CONTACT.get();
        if(c==null||event.isCanceled()||event.getAmount()<=0||event.getSource()!=c.source())return;
        Entity primary=c.target();
        // Multipart bosses route a hit on a part to its living parent, still exactly one victim.
        if(primary instanceof net.minecraftforge.entity.PartEntity<?> part)primary=part.getParent();
        if(event.getEntity()!=primary||!charged(c.player())||!eligible(c.player()))return;
        if(!Erasure.implode(c.player(),event.getEntity(),c.player().getLookAngle()))return;
        // Take the charge before any further callbacks. Keep the accepted punch from killing a weak mob
        // before its 18-tick destruction; Erasure owns the final death, drops and player respawn.
        event.setAmount(0);
        var d=LokiData.get(c.player());
        d.remove(BranchFistState.UNTIL);d.remove(BranchFistState.START);
        d.putLong(BranchFistState.IMPACT,LokiData.now(c.player()));
        LokiNetwork.sync(c.player());
        LokiNetwork.animate(c.player(),"branch_punch");
    }
    private static void notice(ServerPlayer p,String text){p.displayClientMessage(Component.literal(text),true);}
}
