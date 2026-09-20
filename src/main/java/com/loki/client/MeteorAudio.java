package com.loki.client;

import com.loki.Loki;
import com.loki.entity.StarfallEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import java.util.*;

/**
 * The sound of a rock burning through air.
 *
 * <p>A meteor is not an event with a sound at the end of it — it is audible for the whole descent, and it
 * gets louder and lower as it comes. So each one carries a single looping burn that follows it down and
 * tracks its own heat, and the roar of its entry and the arrival itself are separate one-shots from the
 * server. Nothing here is a thunderclap standing in for a fire.
 */
public final class MeteorAudio {
    private MeteorAudio() {}

    private static final Map<Integer,Burn> BURNS=new HashMap<>();

    public static void clear() {
        var manager=Minecraft.getInstance().getSoundManager();
        BURNS.values().forEach(b->{b.retire();manager.stop(b);});
        BURNS.clear();
    }

    /** Called while a meteor is being drawn; starts its burn once and leaves it to look after itself. */
    public static void follow(StarfallEntity star) {
        if(BURNS.containsKey(star.getId()))return;
        if(BURNS.size()>8)return;
        Burn burn=new Burn(star.getId());
        BURNS.put(star.getId(),burn);
        Minecraft.getInstance().getSoundManager().play(burn);
    }

    public static void tick() {
        var mc=Minecraft.getInstance();
        if(mc.level==null){clear();return;}
        BURNS.entrySet().removeIf(entry->{
            if(mc.level.getEntity(entry.getKey()) instanceof StarfallEntity star&&star.isAlive())return false;
            entry.getValue().retire();
            mc.getSoundManager().stop(entry.getValue());
            return true;
        });
    }

    /** One burn, riding the stone, driven by how hard it is burning. */
    private static final class Burn extends AbstractTickableSoundInstance {
        private final int star;
        private boolean retired;
        Burn(int star) {
            super(Loki.METEOR_BURN.get(),SoundSource.WEATHER,SoundInstance.createUnseededRandom());
            this.star=star;
            looping=true;delay=0;volume=.2f;pitch=.5f;
            attenuation=SoundInstance.Attenuation.LINEAR;
        }
        void retire() {retired=true;}
        @Override public void tick() {
            var mc=Minecraft.getInstance();
            if(retired||mc.level==null||!(mc.level.getEntity(star) instanceof StarfallEntity rock)||!rock.isAlive()) {
                volume=0;stop();return;
            }
            x=rock.getX();y=rock.getY();z=rock.getZ();
            float heat=Mth.clamp(rock.heat(),0,1);
            volume=.55f+1.85f*heat;
            // Lower as it accelerates: the sound of something big arriving rather than something small.
            pitch=.62f-heat*.16f;
        }
    }
}
