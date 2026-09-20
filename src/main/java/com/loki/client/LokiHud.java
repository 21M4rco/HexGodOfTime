package com.loki.client;

import com.loki.data.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import java.util.Locale;

/**
 * One small bottom-left ability readout; selecting another slot changes its instructions in place.
 *
 * <p>Underneath it sits the time bar. Those four are not quick-bar entries and never scroll past:
 * they are permanent commands, so they are drawn permanently, each beside the key that fires it.
 */
public final class LokiHud {
    private static final int WIDTH=204,HEIGHT=122;
    /** The dedicated controls, in key order; a null power is the plain resume command. */
    private static final Ability[] CONTROLS={Ability.TIME_STOP,null,Ability.REWIND,Ability.SLOW_FIELD};
    private static final String[] CONTROL_NAMES={"Stop","Resume","Rewind","Slow"};
    private static final float SCALE=.8f;
    public static void render(GuiGraphics g) {
        var mc=Minecraft.getInstance();if(mc.player==null||mc.options.hideGui)return;
        var d=ClientState.self();Ability a=QuickBar.displayed();
        if(a==null)return;
        int screenWidth=mc.getWindow().getGuiScaledWidth(),screenHeight=mc.getWindow().getGuiScaledHeight();
        int bottom=screenHeight-(screenWidth<540?54:8);
        int x=8,y=bottom-(int)(HEIGHT*SCALE);
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(SCALE,SCALE,1);
        g.fill(0,0,WIDTH,HEIGHT,0xb807110d);
        g.fill(0,0,2,HEIGHT,0xff000000|a.discipline.color);
        String title=a.title;
        g.drawString(mc.font,title,7,6,0xe6f3e8,false);
        long cd=Math.max(0,d.getLong("cd_"+a.name())-ClientState.now());
        boolean home=a==Ability.RIFT&&com.loki.server.PocketRealm.inside(mc.player.level());
        String status=home?"Return: free":cd>0?String.format(Locale.ROOT,"Recovery %.1fs",cd/20f):d.getFloat("energy")<a.cost?"Low energy":"Ready";
        g.drawString(mc.font,status+"  |  Cost "+(home?0:a.cost),7,18,cd>0&&!home?0xd2b27f:0x93caaa,false);
        String primary=LokiClient.PRIMARY.getTranslatedKeyMessage().getString();
        String secondary=LokiClient.SECONDARY.getTranslatedKeyMessage().getString();
        // Outside the sanctum the Fracture only does one thing — it takes you and whatever is
        // beside you in — so there is nothing to configure and no selector to offer. Inside, where
        // the break can lead anywhere, the cast key runs whatever the selector last saved.
        boolean fracture=a==Ability.RIFT;
        String head=!fracture?primary(a)
            :home?FractureModes.byId(d.getString("fractureMode")).label(d.getString("fractureTargetName"))
            :"Tap: doorway in. Hold: pull 5 blocks in.";
        var lines=mc.font.split(net.minecraft.network.chat.Component.literal(primary+"  "+head),WIDTH-14);
        for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(mc.font,lines.get(i),7,32+i*10,fracture?0xd8ecdd:0xc9d8ce,false);
        String hint=!fracture?alternate(a):home?"Choose where the break leads":"";
        if(!hint.isEmpty())g.drawString(mc.font,secondary+"  "+hint,7,54,fracture?0xc0b184:0x9cb6a6,false);
        String select=LokiClient.SELECT.getTranslatedKeyMessage().getString();
        g.drawString(mc.font,select+" + scroll: choose ability",7,67,0x779d87,false);
        float max=100+MasteryScreen.mastery(d,Discipline.TEMPORAL)*.2f+(d.getBoolean("ascended")?150:0),energy=d.getFloat("energy");
        String value="Energy "+Math.round(energy)+" / "+Math.round(max);
        g.drawString(mc.font,value,7,82,0xb3cbbd,false);
        int start=mc.font.width(value)+14;
        g.fill(start,84,WIDTH-7,88,0xff263e31);
        g.fill(start,84,start+(int)((WIDTH-7-start)*Math.max(0,Math.min(1,energy/max))),88,0xff74d8a6);
        controls(g,d,energy,94);
        if(QuickBar.open())QuickBar.renderChoices(g,0,-39,WIDTH);
        else if(d.getBoolean("ascended")) {
            String flight=LokiClient.FLIGHT.getTranslatedKeyMessage().getString()+": "+(d.getBoolean("cosmicFlying")?"flying (Space / crouch)":"flight");
            g.drawString(mc.font,flight,3,-12,0xaadabd,false);
        }
        g.pose().popPose();
        if(ClientState.frozen(mc.player.getId()))g.drawCenteredString(mc.font,"BETWEEN MOMENTS",screenWidth/2,15,0xd8d6be);
    }
    /** The permanent time commands: key, name and state, always on screen and never scrollable. */
    private static void controls(GuiGraphics g,net.minecraft.nbt.CompoundTag d,float energy,int top) {
        var mc=Minecraft.getInstance();
        g.fill(0,top-3,WIDTH,top-2,0xff1f3229);
        g.drawString(mc.font,"TIME CONTROL",7,top,0xc6b98a,false);
        int cell=(WIDTH-14)/CONTROLS.length;
        for(int i=0;i<CONTROLS.length;i++) {
            Ability a=CONTROLS[i];
            int x=7+i*cell;
            boolean locked=a!=null&&!MasteryScreen.unlocked(d,a);
            long cd=a==null?0:Math.max(0,d.getLong("cd_"+a.name())-ClientState.now());
            boolean poor=a!=null&&energy<a.cost;
            int accent=locked?0xff44443a:cd>0?0xffbb9256:poor?0xff8f6f5a:0xffcf9f56;
            g.fill(x,top+11,x+cell-3,top+24,0xa6091612);
            g.fill(x,top+11,x+1,top+24,accent);
            String key=LokiClient.TIME_KEYS[i].getTranslatedKeyMessage().getString();
            g.drawString(mc.font,key,x+4,top+14,locked?0x6f6f60:0xe9f3ec,false);
            int nameX=x+5+Math.max(8,mc.font.width(key));
            String label=locked?"Locked":cd>0?String.format(Locale.ROOT,"%.0fs",cd/20f):CONTROL_NAMES[i];
            g.drawString(mc.font,label,nameX,top+14,locked?0x6d6d5e:cd>0?0xd6b284:poor?0xb08f79:0xd8e6d5,false);
        }
    }

    private static String primary(Ability a) {
        return switch(a) {
            case RIFT -> "Tap: portal. Hold: pull 5 blocks.";
            case DUPLICATE -> "Create a living decoy.";
            case PROJECTION_SWAP -> "Swap with your nearest decoy.";
            case MASQUERADE -> "Copy a humanoid appearance.";
            case MIRAGE -> "Create decoys and briefly vanish.";
            case ARCHITECTURE -> "Hold: raise an illusory building.";
            case BOLT -> "Fire an emerald magic bolt.";
            case PUSH -> "Push nearby enemies away.";
            case TELEKINESIS -> "Grab target. Scroll: move it.";
            case BLINK -> "Teleport toward your aim.";
            case WARD -> "Raise a defensive veil.";
            case DAGGERS -> "Conjure a dagger in an empty hand.";
            case TWIN_DAGGERS -> "Conjure twin daggers. Use: throw.";
            case LAEVATEINN -> "Conjure the Void sword.";
            case ENCHANT -> "Charm a creature to follow you.";
            case MEMORY -> "Reveal a target's recent steps.";
            case TIME_SLIP -> "Slip to a recent moment.";
            case REWIND -> "Rewind position and some health.";
            case SLOW_FIELD -> "Slow nearby creatures and shots.";
            case TIME_STOP -> "Freeze the local battlefield.";
            case SELECTIVE_STOP -> "Freeze the target in your aim.";
            case THREADS -> "Bind your target in time.";
            case ASCENSION -> "Toggle your final transformation.";
        };
    }
    private static String alternate(Ability a) {
        return switch(a) {
            case RIFT -> "Close portal / open exit";
            case DUPLICATE -> "Direct / dismiss decoys";
            case PROJECTION_SWAP -> "Place a decoy at your aim";
            case MASQUERADE -> "Remove disguise";
            case ARCHITECTURE -> "Change building design";
            case BOLT -> "Stronger impact";
            case TELEKINESIS -> "Throw held target";
            case DAGGERS,TWIN_DAGGERS,LAEVATEINN -> "Dismiss weapons";
            case ENCHANT -> "Direct charmed creatures";
            case SLOW_FIELD,TIME_STOP -> "Resume time";
            case SELECTIVE_STOP -> "Exempt an ally";
            case THREADS -> "Pull bound target";
            default -> "Same action";
        };
    }
}
