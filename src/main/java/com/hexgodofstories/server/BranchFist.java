package com.hexgodofstories.server;

import com.hexgodofstories.data.*;
import com.hexgodofstories.network.HexNetwork;
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

    public static boolean charged(Player p) {return HexData.get(p).getLong(BranchFistState.UNTIL)>HexData.now(p);}
    private static boolean eligible(ServerPlayer p) {
        return p.isAlive()&&!p.isSpectator()&&Transformation.transformed(p)
            &&!TemporalEngine.frozen(p)&&!Erasure.erasing(p)&&!TimeBranch.charging(p);
    }
    public static void arm(ServerPlayer p) {
        if(!HexData.unlocked(p,Ability.TIME_BRANCH))return;
        if(!eligible(p)){notice(p,"The Time Branches answer only the transformed and free.");return;}
        var d=HexData.get(p);long now=HexData.now(p);
        if(charged(p))return;
        if(d.getLong(BranchFistState.COOLDOWN)>now){notice(p,"The charged fist is recovering.");return;}
        if(!HexData.spend(p,BranchFistState.COST)){notice(p,"Not enough Temporal Energy.");return;}
        d.putLong(BranchFistState.START,now);d.putLong(BranchFistState.UNTIL,now+BranchFistState.WINDOW);
        d.putLong(BranchFistState.COOLDOWN,now+BranchFistState.RECOVERY);
        d.remove(BranchFistState.IMPACT);
        HexNetwork.sync(p);
    }
    public static void tick(ServerPlayer p) {
        var d=HexData.get(p);
        if(d.contains(BranchFistState.UNTIL)&&(!charged(p)||!eligible(p)))clear(p);
    }
    public static void clear(ServerPlayer p) {
        var d=HexData.get(p);
        if(!d.contains(BranchFistState.UNTIL))return;
        d.remove(BranchFistState.UNTIL);d.remove(BranchFistState.START);
        HexNetwork.sync(p);
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
        // before its gradual destruction; Erasure owns the final death, drops and player respawn.
        event.setAmount(0);
        var d=HexData.get(c.player());
        d.remove(BranchFistState.UNTIL);d.remove(BranchFistState.START);
        d.putLong(BranchFistState.IMPACT,HexData.now(c.player()));
        HexNetwork.sync(c.player());
        HexNetwork.animate(c.player(),"branch_punch");
    }
    private static void notice(ServerPlayer p,String text){p.displayClientMessage(Component.literal(text),true);}
}
