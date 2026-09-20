package com.loki.data;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/** Persistent progression uses the foundation's compact player-NBT and XP curve. Transient control state lives on the server. */
public final class LokiData {
    public static final int QUICK_SLOTS=8;
    public static CompoundTag get(Player p) {
        CompoundTag parent=p.getPersistentData();
        if(!parent.contains("Loki")) {CompoundTag n=new CompoundTag();n.putFloat("energy",100);parent.put("Loki",n);}
        return parent.getCompound("Loki");
    }
    public static int mastery(Player p,Discipline d) {return MasteryCurve.levelForXp(get(p).getLong("xp_"+d.name()));}
    public static void mastery(Player p,Discipline d,int level) {get(p).putLong("xp_"+d.name(),MasteryCurve.xpForLevel(level));}
    public static void train(Player p,Discipline d,int xp) {get(p).putLong("xp_"+d.name(),Math.min(MasteryCurve.MAX_XP,get(p).getLong("xp_"+d.name())+Math.max(0,xp)));}
    public static boolean unlocked(Player p,Ability a) {
        if(get(p).getBoolean("unlock_"+a.name()))return true;
        int total=0;for(Discipline d:Discipline.values())if(d!=Discipline.TEMPORAL&&d!=Discipline.PURPOSE)total+=mastery(p,d);
        if(a.discipline==Discipline.TEMPORAL&&total<600)return false;
        if(a.discipline==Discipline.PURPOSE&&mastery(p,Discipline.TEMPORAL)<800)return false;
        return mastery(p,a.discipline)>=a.level;
    }
    public static float maxEnergy(Player p) {return 100+mastery(p,Discipline.TEMPORAL)*.2f+(get(p).getBoolean("ascended")?150:0);}
    public static float energy(Player p) {return Math.max(0,Math.min(maxEnergy(p),get(p).getFloat("energy")));}
    public static void energy(Player p,float v) {get(p).putFloat("energy",Math.max(0,Math.min(maxEnergy(p),v)));}
    public static boolean spend(Player p,float v) {if(energy(p)<v)return false;energy(p,energy(p)-v);return true;}
    public static long now(Player p) {return p.level().getGameTime();}
    public static int cooldown(Player p,Ability a) {return (int)Math.max(0,get(p).getLong("cd_"+a.name())-now(p));}
    public static Ability selected(Player p) {
        Ability a=Ability.at(get(p).getInt("selected"));
        if(!a.dedicated)return a;
        // A save from before the time keys existed can still point the cast key at one of them.
        for(int id:quick(p)){Ability slotted=Ability.slot(id);if(slotted!=null)return slotted;}
        return Ability.DUPLICATE;
    }

    /** Eight persisted shortcuts. Empty slots hold -1 so an unlock can claim them without disturbing a chosen layout. */
    public static int[] quick(Player p) {
        int[] slots=get(p).getIntArray("quick");
        if(slots.length!=QUICK_SLOTS) {int[] fresh=new int[QUICK_SLOTS];java.util.Arrays.fill(fresh,-1);get(p).putIntArray("quick",fresh);return fresh;}
        // The time controls moved onto permanent keys; evict them from layouts saved before that change.
        boolean evicted=false;
        for(int i=0;i<QUICK_SLOTS;i++) {
            Ability a=Ability.slot(slots[i]);
            if(a!=null&&a.dedicated){slots[i]=-1;evicted=true;}
        }
        if(evicted)get(p).putIntArray("quick",slots);
        return slots;
    }
    public static void quick(Player p,int slot,int ability) {
        if(slot<0||slot>=QUICK_SLOTS)return;
        Ability chosen=Ability.slot(ability);
        if(chosen!=null&&chosen.dedicated)return;
        int[] slots=quick(p);
        for(int i=0;i<QUICK_SLOTS;i++)if(slots[i]==ability)slots[i]=-1;
        slots[slot]=ability;get(p).putIntArray("quick",slots);
    }
    /** Fills empty shortcuts with newly unlocked abilities so progression is immediately reachable from the bar. */
    public static void refreshQuick(Player p) {
        int[] slots=quick(p);boolean changed=false;
        // A layout saved before the time keys existed can still have one of them selected.
        Ability chosen=Ability.at(get(p).getInt("selected"));
        if(chosen.dedicated)get(p).putInt("selected",selected(p).ordinal());
        for(int i=0;i<QUICK_SLOTS;i++)if(slots[i]>=0&&!unlocked(p,Ability.at(slots[i]))){slots[i]=-1;changed=true;}
        for(Ability a:Ability.values()) {
            if(a.dedicated||!unlocked(p,a))continue;
            boolean present=false;for(int v:slots)if(v==a.ordinal())present=true;
            if(present)continue;
            for(int i=0;i<QUICK_SLOTS;i++)if(slots[i]<0){slots[i]=a.ordinal();changed=true;break;}
        }
        if(changed)get(p).putIntArray("quick",slots);
    }
    /**
     * The saved Fracture mode. Player NBT, so it outlives the menu closing, repeated casts,
     * dimension changes, death and a server restart alike — and it is never cleared by use.
     */
    public static FractureMode fractureMode(Player p) {return FractureModes.byId(get(p).getString("fractureMode"));}
    public static void fractureMode(Player p,FractureMode mode) {get(p).putString("fractureMode",mode.id);}
    public static java.util.UUID fractureTarget(Player p) {
        CompoundTag d=get(p);
        return d.hasUUID("fractureTarget")?d.getUUID("fractureTarget"):null;
    }
    public static String fractureTargetName(Player p) {return get(p).getString("fractureTargetName");}
    public static void fractureTarget(Player p,java.util.UUID id,String name) {
        CompoundTag d=get(p);
        if(id==null){d.remove("fractureTarget");d.remove("fractureTargetName");return;}
        d.putUUID("fractureTarget",id);d.putString("fractureTargetName",name==null?"":name);
    }

    public static void clearTransient(Player p,boolean death) {
        CompoundTag d=get(p);d.remove("disguise");d.remove("vanishUntil");d.remove("wardUntil");d.remove("held");d.remove("transformStart");d.remove("grip");
        d.remove(BranchFistState.UNTIL);d.remove(BranchFistState.START);d.remove(BranchFistState.IMPACT);
        if(death)d.putBoolean("ascended",false);
    }
}
