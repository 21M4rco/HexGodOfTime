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
    public static Ability selected(Player p) {return Ability.at(get(p).getInt("selected"));}

    /** Eight persisted shortcuts. Empty slots hold -1 so an unlock can claim them without disturbing a chosen layout. */
    public static int[] quick(Player p) {
        int[] slots=get(p).getIntArray("quick");
        if(slots.length!=QUICK_SLOTS) {int[] fresh=new int[QUICK_SLOTS];java.util.Arrays.fill(fresh,-1);get(p).putIntArray("quick",fresh);return fresh;}
        return slots;
    }
    public static void quick(Player p,int slot,int ability) {
        if(slot<0||slot>=QUICK_SLOTS)return;
        int[] slots=quick(p);
        for(int i=0;i<QUICK_SLOTS;i++)if(slots[i]==ability)slots[i]=-1;
        slots[slot]=ability;get(p).putIntArray("quick",slots);
    }
    /** Fills empty shortcuts with newly unlocked abilities so progression is immediately reachable from the bar. */
    public static void refreshQuick(Player p) {
        int[] slots=quick(p);boolean changed=false;
        for(int i=0;i<QUICK_SLOTS;i++)if(slots[i]>=0&&!unlocked(p,Ability.at(slots[i]))){slots[i]=-1;changed=true;}
        for(Ability a:Ability.values()) {
            if(!unlocked(p,a))continue;
            boolean present=false;for(int v:slots)if(v==a.ordinal())present=true;
            if(present)continue;
            for(int i=0;i<QUICK_SLOTS;i++)if(slots[i]<0){slots[i]=a.ordinal();changed=true;break;}
        }
        if(changed)get(p).putIntArray("quick",slots);
    }
    public static void clearTransient(Player p,boolean death) {
        CompoundTag d=get(p);d.remove("disguise");d.remove("vanishUntil");d.remove("wardUntil");d.remove("held");d.remove("transformStart");d.remove("grip");
        if(death)d.putBoolean("ascended",false);
    }
}
