package com.hexgodofstories.client;

import com.hexgodofstories.HexGodOfStories;
import com.hexgodofstories.data.BranchCharge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.sounds.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The sound of containing something that should not be containable.
 *
 * <p>Built as layers rather than as one cue: a deep hum underneath the whole charge, a resonance that
 * rises with it, a shimmer on top, a bass pressure that only arrives once the membrane is struggling, and
 * discrete crackles whose rate follows the instability. Each layer is one looping instance whose volume
 * and pitch are driven per tick from the charge the server already synchronised, so every nearby player
 * hears the same build with no audio packets at all.
 *
 * <p>Nothing here is a vanilla explosion or a thunderclap. The discharge is a resonant opening, and the
 * torrent carries its own sustained roar for as long as it is running.
 */
public final class BranchAudio {
    private BranchAudio() {}

    /** One tick of held silence before the discharge lands — the drop that makes the release hit. */
    public static final int DROP=2;

    private static final Map<Integer,List<Layer>> CHARGES=new HashMap<>();
    private static final List<Roar> ROARS=new ArrayList<>();
    private static final Set<Integer> ANNOUNCED=new HashSet<>();

    public static void clear() {
        var manager=Minecraft.getInstance().getSoundManager();
        CHARGES.values().forEach(list->list.forEach(l->{l.retire();manager.stop(l);}));
        ROARS.forEach(r->{r.retire();manager.stop(r);});
        CHARGES.clear();ROARS.clear();ANNOUNCED.clear();
    }

    /** Called once a client tick. Starts, drives and retires every layer from the charge state alone. */
    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level==null){clear();return;}
        var manager=mc.getSoundManager();
        CHARGES.entrySet().removeIf(entry->{
            if(TimeBranchRenderer.charging(entry.getKey())&&mc.level.getEntity(entry.getKey())!=null)return false;
            entry.getValue().forEach(l->{l.retire();manager.stop(l);});
            ANNOUNCED.remove(entry.getKey());
            return true;
        });
        ROARS.removeIf(r->r.isStopped());

        for(var player:mc.level.players()) {
            int held=TimeBranchRenderer.held(player.getId(),0);
            if(held<0)continue;
            if(!CHARGES.containsKey(player.getId())) {
                List<Layer> layers=new ArrayList<>();
                layers.add(new Layer(player.getId(),HexGodOfStories.BRANCH_HUM.get(),.62f,.68f,0,1.7f));
                layers.add(new Layer(player.getId(),HexGodOfStories.BRANCH_RESONANCE.get(),.52f,.82f,0,1.0f));
                layers.add(new Layer(player.getId(),HexGodOfStories.BRANCH_SHIMMER.get(),.30f,1.24f,BranchCharge.STABLE,.8f));
                layers.add(new Layer(player.getId(),HexGodOfStories.BRANCH_PRESSURE.get(),.70f,.56f,BranchCharge.PRESSURE,1.0f));
                layers.forEach(manager::play);
                CHARGES.put(player.getId(),layers);
            }
            // Crackling that gets ahead of itself as the membrane stops coping.
            int stage=BranchCharge.stage(held);
            if(stage>=2&&ClientState.now()%Math.max(2,9-stage)==0)
                play(HexGodOfStories.BRANCH_CRACKLE.get(),player.position(),.16f+.05f*stage,.9f+mc.level.random.nextFloat()*.5f);
            // Full power available: one unmistakable cue, then the choice to keep holding is theirs.
            if(held>=BranchCharge.FULL&&ANNOUNCED.add(player.getId()))
                play(HexGodOfStories.BRANCH_READY.get(),player.position(),.95f,1.28f);
        }
    }

    /** The release: the resonant opening, then the roar that runs with the torrent. */
    public static void discharge(Vec3 at,float power) {
        var mc=Minecraft.getInstance();
        if(mc.level==null)return;
        play(HexGodOfStories.BRANCH_OPEN.get(),at,1.25f,.78f-power*.12f);
        Roar roar=new Roar(at,power);
        ROARS.add(roar);
        mc.getSoundManager().play(roar);
    }
    public static void erase(Vec3 at) {play(HexGodOfStories.BRANCH_ERASE.get(),at,.65f,1.42f);}

    private static void play(SoundEvent sound,Vec3 at,float volume,float pitch) {
        var mc=Minecraft.getInstance();
        if(mc.level==null)return;
        mc.getSoundManager().play(new SimpleSoundInstance(sound,SoundSource.PLAYERS,
            volume,pitch,SoundInstance.createUnseededRandom(),at.x,at.y,at.z));
    }

    /** One layer of the charge, following the caster and driven by how long they have held. */
    private static final class Layer extends AbstractTickableSoundInstance {
        private final int caster;private final float peak,basePitch,bend;private final int from;
        private boolean retired;
        Layer(int caster,SoundEvent sound,float peak,float basePitch,int from,float bend) {
            super(sound,SoundSource.PLAYERS,SoundInstance.createUnseededRandom());
            this.caster=caster;this.peak=peak;this.basePitch=basePitch;this.from=from;this.bend=bend;
            looping=true;delay=0;volume=.001f;pitch=basePitch;
            attenuation=SoundInstance.Attenuation.LINEAR;
        }
        void retire() {retired=true;}
        @Override public void tick() {
            var mc=Minecraft.getInstance();
            Entity source=mc.level==null?null:mc.level.getEntity(caster);
            int held=TimeBranchRenderer.held(caster,0);
            if(retired||source==null||held<0){volume=0;stop();return;}
            x=source.getX();y=source.getEyeY();z=source.getZ();
            float open=Mth.clamp((held-from)/26f,0,1);
            float charge=BranchCharge.power(held);
            float over=BranchCharge.overcharge(held);
            volume=peak*open*(.35f+.65f*charge);
            // Drifting sharp through the build, then wavering once containment is genuinely failing.
            pitch=basePitch+charge*(bend-1)*.5f+over*.16f
                +(held>=BranchCharge.CRITICAL?Mth.sin(held*.9f)*.055f*(1+over):0);
        }
    }

    /** The torrent's sustained roar. It ends itself with the beam. */
    private static final class Roar extends AbstractTickableSoundInstance {
        private final long start;private final int life;
        private boolean retired;
        Roar(Vec3 at,float power) {
            super(HexGodOfStories.BRANCH_ROAR.get(),SoundSource.PLAYERS,SoundInstance.createUnseededRandom());
            x=at.x;y=at.y;z=at.z;
            looping=true;delay=DROP;volume=1.15f;pitch=.62f+power*.1f;
            attenuation=SoundInstance.Attenuation.LINEAR;
            start=ClientState.now();life=BranchCharge.life(BranchCharge.RANGE,power);
        }
        void retire() {retired=true;}
        @Override public void tick() {
            long age=ClientState.now()-start;
            if(retired||age>life){volume=0;stop();return;}
            volume=1.15f*Mth.clamp((life-age)/(float)BranchCharge.FADE,0,1);
        }
    }
}
