package com.hexgodofstories.client;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/** A dense blue-white projectile with a moving white core and a broad floor detonation. */
public final class ScepterFx {
    private ScepterFx() { }
    private static final DustParticleOptions ELECTRIC=new DustParticleOptions(new Vector3f(.08f,.35f,1f),2.1f);
    private static final DustParticleOptions AZURE=new DustParticleOptions(new Vector3f(.14f,.80f,1f),1.7f);
    private static final DustParticleOptions WHITE=new DustParticleOptions(new Vector3f(.93f,.98f,1f),1.4f);
    private record Shot(Vec3 start,Vec3 end,long born,int travel) { }
    private static final List<Shot> SHOTS=new ArrayList<>();

    public static void clear(){SHOTS.clear();}

    /** Photon-bright layered core, continuous over all 100 blocks rather than disconnected particle puffs. */
    public static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,float partial) {
        double now=ClientState.now()+partial;
        SHOTS.removeIf(shot->now-shot.born>shot.travel+6);
        if(SHOTS.isEmpty())return;
        var type=BranchVfx.glow();var out=buffers.getBuffer(type);
        for(Shot shot:SHOTS) {
            double age=now-shot.born;
            if(age<0)continue;
            double progress=Math.min(1,age/shot.travel);
            Vec3 direction=shot.end.subtract(shot.start);
            Vec3 head=shot.start.add(direction.scale(progress));
            float fade=(float)Math.min(1,Math.max(0,(shot.travel+6-age)/6));
            double pulse=.8+.2*Math.sin(age*1.7);
            BranchVfx.ribbon(pose,out,shot.start,head,.43*pulse,0x0569fa,.42f*fade);
            BranchVfx.ribbon(pose,out,shot.start,head,.18*pulse,0x53caff,.88f*fade);
            BranchVfx.ribbon(pose,out,shot.start,head,.055,0xf5ffff,.98f*fade);
            BranchVfx.billboard(pose,out,head,.68,age*.14,0x168cff,.75f*fade);
            BranchVfx.billboard(pose,out,head,.31,-age*.19,0xffffff,.97f*fade);
        }
        buffers.endBatch(type);
    }

    public static void blast(Vec3 origin,Vec3 destination,boolean floor) {
        Vec3 ray=destination.subtract(origin);
        if(ray.lengthSqr()<.01)return;
        Vec3 direction=ray.normalize();
        int duration=Math.max(5,Math.min(13,(int)Math.ceil(ray.length()/11)));
        if(SHOTS.size()>=12)SHOTS.remove(0);
        SHOTS.add(new Shot(origin,destination,ClientState.now(),duration));
        Vfx.bloom(-1,origin,direction,duration+5,(at,aim,t)->{
            float travelled=Math.min(1,t*(duration+5)/(float)duration);
            Vec3 head=at.add(ray.scale(travelled));
            if(travelled<1) {
                // Bright saturated centre; the blue wake fans out in the firing direction.
                Vfx.cloud(WHITE,head,.24,11,.01);
                Vfx.cloud(AZURE,head,.57,13,.025);
                Vfx.cloud(ELECTRIC,head,1.05,9,.035);
                Vfx.spark(ParticleTypes.END_ROD,head,Vec3.ZERO);
                for(int i=1;i<=5;i++) {
                    Vec3 wake=head.subtract(direction.scale(i*.37));
                    Vfx.cloud(i%2==0?WHITE:AZURE,wake,.12+i*.065,4,.01);
                }
            } else if(t<duration/(float)(duration+5)+.12f) {
                int burst=floor?30:18;
                Vfx.cloud(WHITE,destination,.5,burst,.13);
                Vfx.cloud(AZURE,destination,floor?2:1.2,burst,.16);
                Vfx.cone(ELECTRIC,destination,new Vec3(0,.5,0),burst,.15,floor?.42:.28);
                if(floor) {
                    Vfx.ring(AZURE,destination,.5,20,.15,.04);
                    Vfx.ring(ParticleTypes.FLAME,destination,1.7,16,.03,.08);
                }
            }
        });
    }
}
