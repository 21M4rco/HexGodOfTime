package com.hexgodofstories.client;

import com.hexgodofstories.data.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import java.util.Locale;

/**
 * One small bottom-left ability readout, with the seven bind slots in a strip over it, each under its key;
 * choosing another slot changes the readout's instructions in place. While the full transformation is worn, an
 * ability it changes is described as the variant it has become, in gold.
 *
 * <p>Underneath it sits the time bar. Those controls are never bound to a slot: they are permanent commands, so
 * they are drawn permanently, each beside the key that fires it.
 */
public final class HexHud {
    private static final int WIDTH=204,HEIGHT=122;
    /** The dedicated controls, in key order. Stop toggles; R also resumes an active stop. */
    private static final Ability[] CONTROLS={Ability.TIME_STOP,Ability.REWIND,Ability.TIME_BRANCH};
    private static final String[] CONTROL_NAMES={"Stop","Rewind","Branch"};
    private static final float SCALE=.8f;
    public static void render(GuiGraphics g) {
        var mc=Minecraft.getInstance();if(mc.player==null||mc.options.hideGui)return;
        var d=ClientState.self();if(!d.getBoolean("abilitiesEnabled"))return;Ability a=QuickBar.displayed();
        int screenWidth=mc.getWindow().getGuiScaledWidth(),screenHeight=mc.getWindow().getGuiScaledHeight();
        // Drawn before the ability panel and independently of it: a caster holding the torrent needs the
        // charge read-out even if their quick bar happens to be empty.
        BranchMeter.render(g,screenWidth,screenHeight);
        if(a==null)return;
        // Bottom right, clear of the chat (which owns the bottom left) and of the hotbar on any screen wide enough.
        int bottom=screenHeight-(screenWidth<540?54:8);
        int x=screenWidth-8-(int)(WIDTH*SCALE),y=bottom-(int)(HEIGHT*SCALE);
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(SCALE,SCALE,1);
        HexUi.rounded(g,0,0,WIDTH,HEIGHT,0xd00a1310);
        HexUi.outline(g,0,0,WIDTH,HEIGHT,HexUi.BORDER);
        g.fill(1,2,3,HEIGHT-2,0xff000000|a.discipline.color);
        String title=a.title;
        // Which key chooses it, so the binding is readable without opening the archive.
        int bound=QuickBar.slotOf(d,a);
        boolean variant=d.getBoolean("ascended")&&Ascended.changes(a);
        if(bound>=0) {
            String tag="KEY "+QuickBar.shortKey(QuickBar.key(bound));
            g.drawString(mc.font,tag,WIDTH-7-mc.font.width(tag),6,0x84a892,false);
        }
        if(variant)title=Ascended.title(a);
        g.drawString(mc.font,title,7,6,variant?0xf0d58a:0xe6f3e8,false);
        long cd=Math.max(0,d.getLong("cd_"+a.name())-ClientState.now());
        boolean home=a==Ability.RIFT&&com.hexgodofstories.server.PocketRealm.inside(mc.player.level())||a==Ability.WARPING&&com.hexgodofstories.warping.Destination.from(mc.player.level())!=null;
        String status=home?"Return: free":cd>0?String.format(Locale.ROOT,"Recovery %.1fs",cd/20f):d.getFloat("energy")<a.cost?"Low energy":"Ready";
        g.drawString(mc.font,status+"  |  Cost "+(home?0:a.cost),7,18,cd>0&&!home?0xd2b27f:0x93caaa,false);
        int above=-QuickBar.height()-4;
        if(a==Ability.WARPING){String charge=WarpRenderer.chargeLabel(mc.player.getId());if(!charge.isEmpty())g.drawString(mc.font,mc.font.plainSubstrByWidth(charge,WIDTH-6),3,above+(d.getBoolean("ascended")?-24:-12),0xd7b9f0,false);}
        String primary=HexClient.PRIMARY.getTranslatedKeyMessage().getString();
        String secondary=HexClient.SECONDARY.getTranslatedKeyMessage().getString();
        // Outside the sanctum the Fracture only does one thing — it takes you and whatever is
        // beside you in — so there is nothing to configure and no selector to offer. Inside, where
        // the break can lead anywhere, the cast key runs whatever the selector last saved.
        boolean fracture=a==Ability.RIFT;
        String head=variant?Ascended.hud(a):!fracture?primary(a)
            :home?FractureModes.byId(d.getString("fractureMode")).label(d.getString("fractureTargetName"))
            :"Tap: doorway in. Hold: pull 5 blocks in.";
        if(a==Ability.WARPING&&home)head="Leave this dimension freely.";
        // The key that casts it: its own slot's, or the cast key for one not bound to any.
        String castKey=bound>=0?QuickBar.shortKey(QuickBar.key(bound)):primary;
        var lines=mc.font.split(net.minecraft.network.chat.Component.literal(castKey+"  "+head),WIDTH-14);
        for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(mc.font,lines.get(i),7,32+i*10,variant?0xe8d49c:fracture?0xd8ecdd:0xc9d8ce,false);
        // The second line: the spell's second move, on its own key held (the key named first), or G for Warping's
        // destination, the one spell that still has a key of its own for it.
        String release=QuickBar.shortKey(HexClient.RELEASE.getTranslatedKeyMessage().getString());
        String hint=fracture?(home?"Choose where the break leads":""):QuickBar.held(a);
        String lead=fracture?secondary:hint.isEmpty()?"":"Hold "+castKey;
        if(a==Ability.WARPING){hint=com.hexgodofstories.warping.Destination.at(d.getInt("warpDestination")).title;lead=secondary;}
        if(a==Ability.TELEKINESIS){hint="Again: throw  \u00b7  "+release+": let go";lead="";}
        if(a==Ability.ARCHITECTURE){hint=release+": take the wall down";lead="";}
        if(a==Ability.DAGGERS||a==Ability.TWIN_DAGGERS) {
            // The combo starters keep their own recoveries, apart from the conjuring's shown above.
            boolean dagger=a==Ability.DAGGERS;
            long combo=d.getLong(dagger?com.hexgodofstories.server.BladeCombo.FLURRY_READY:com.hexgodofstories.server.BladeCombo.CUTS_READY)-ClientState.now();
            int whole=dagger?com.hexgodofstories.server.BladeCombo.FLURRY_RECOVERY:com.hexgodofstories.server.BladeCombo.CUTS_RECOVERY;
            if(combo>0&&combo<=whole)hint=String.format(Locale.ROOT,"%s %.1fs",dagger?"Flurry":"Master Cuts",combo/20f);
        }
        if(a==Ability.THREADS) {
            // Gravity Grasp keeps its own recovery, apart from Anchor Being's shown above.
            long grasp=d.getLong(com.hexgodofstories.server.GravityGrasp.READY)-ClientState.now();
            if(grasp>0&&grasp<=com.hexgodofstories.server.GravityGrasp.RECOVERY)hint=String.format(Locale.ROOT,"Gravity Grasp %.1fs",grasp/20f);
        }
        if(a==Ability.ARSENAL) {
            // Gotcha! is the tap, and keeps its own recovery, apart from the crown's shown above; worn, the mantle
            // makes it a swarm.
            hint=d.getBoolean("ascended")?"Gotcha! Swarm":"Gotcha!";lead="Tap "+castKey;
            long gotcha=d.getLong(com.hexgodofstories.server.Arsenal.GOTCHA_READY)-ClientState.now();
            if(gotcha>0&&gotcha<=com.hexgodofstories.server.Arsenal.SWARM_RECOVERY)hint=String.format(Locale.ROOT,"%s %.1fs",hint,gotcha/20f);
        }
        if(a==Ability.TIME_STOP){hint="Press "+HexClient.TIME_STOP.getTranslatedKeyMessage().getString()+" again to resume";lead="";}
        if(a==Ability.TIME_BRANCH) {
            long remaining=Math.max(0,d.getLong(BranchFistState.UNTIL)-ClientState.now());
            long fistCd=Math.max(0,d.getLong(BranchFistState.COOLDOWN)-ClientState.now());
            hint=remaining>0?String.format(Locale.ROOT,"Right fist: %.1fs — punch",remaining/20f)
                :fistCd>0?String.format(Locale.ROOT,"Tap recovery %.1fs",fistCd/20f):"Tap ready | Cost "+BranchFistState.COST;
        }
        if(a==Ability.TIME_BRANCH)lead="";
        if(!hint.isEmpty())g.drawString(mc.font,HexUi.fit(mc.font,(lead.isEmpty()?"":lead+"  ")+hint,WIDTH-14),7,54,fracture?0xc0b184:0x9cb6a6,false);
        String first=QuickBar.shortKey(QuickBar.key(0)),last=QuickBar.shortKey(QuickBar.key(HexData.QUICK_SLOTS-1));
        g.drawString(mc.font,HexUi.fit(mc.font,first+"\u2013"+last+" tap: cast \u00b7 hold: more \u00b7 "+primary+" again",WIDTH-14),7,67,0x779d87,false);
        float max=HexData.MAX_ENERGY,energy=d.getFloat("energy");
        String value="Energy "+Math.round(energy)+" / "+Math.round(max);
        g.drawString(mc.font,value,7,82,0xb3cbbd,false);
        int start=mc.font.width(value)+14;
        g.fill(start,84,WIDTH-7,88,0xff263e31);
        g.fill(start,84,start+(int)((WIDTH-7-start)*Math.max(0,Math.min(1,energy/max))),88,0xff74d8a6);
        controls(g,d,energy,94);
        QuickBar.render(g,(WIDTH-QuickBar.width())/2,above);
        if(d.getBoolean("ascended")) {
            boolean flying=d.getBoolean("cosmicFlying"),grounded=mc.level!=null&&mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
            String flight=QuickBar.shortKey(HexClient.FLIGHT.getTranslatedKeyMessage().getString())+": "+(grounded?"no flight here":flying?"flying":"flight")
                +"  \u00b7  mantle -"+(flying?com.hexgodofstories.server.Transformation.FLYING_DRAIN:com.hexgodofstories.server.Transformation.DRAIN)+"/s";
            g.drawString(mc.font,mc.font.plainSubstrByWidth(flight,WIDTH-6),3,above-12,0xaadabd,false);
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
            boolean locked=!MasteryScreen.unlocked(d,a);
            long cd=Math.max(0,d.getLong("cd_"+a.name())-ClientState.now());
            // Stopping time takes the full transformation; outside it the key only says so.
            boolean mantle=a==Ability.TIME_STOP&&!d.getBoolean("ascended")&&!d.getBoolean("timeStopped");
            boolean poor=energy<a.cost||mantle;
            int accent=locked?0xff44443a:cd>0?0xffbb9256:poor?0xff8f6f5a:0xffcf9f56;
            g.fill(x,top+11,x+cell-3,top+24,0xa6091612);
            g.fill(x,top+11,x+1,top+24,accent);
            String key=QuickBar.shortKey(HexClient.TIME_KEYS[i].getTranslatedKeyMessage().getString());
            g.drawString(mc.font,key,x+4,top+14,locked?0x6f6f60:0xe9f3ec,false);
            int nameX=x+5+Math.max(8,mc.font.width(key));
            String label=locked?"Locked":a==Ability.TIME_STOP&&d.getBoolean("timeStopped")?"Resume":mantle?"Mantle":cd>0?String.format(Locale.ROOT,"%.0fs",cd/20f):CONTROL_NAMES[i];
            g.drawString(mc.font,label,nameX,top+14,locked?0x6d6d5e:cd>0?0xd6b284:poor?0xb08f79:0xd8e6d5,false);
        }
    }

    private static String primary(Ability a) {
        return switch(a) {
            case WARPING -> "Hold/release: open 10s. Step in: follow.";
            case RIFT -> "Tap: doorway. Hold: pull 5 blocks.";
            case DUPLICATE -> "Create a living decoy.";
            case PROJECTION_SWAP -> "Swap with your nearest decoy.";
            case MASQUERADE -> "Copy a humanoid appearance.";
            case MIRAGE -> "Create decoys and briefly vanish.";
            case ARCHITECTURE -> "Hold: raise a wall, Small to Massive.";
            case BOLT -> "Hurl a bolt of emerald seidr.";
            case PUSH -> "Push nearby enemies away.";
            case TELEKINESIS -> "Grab; again: throw. Scroll: closer/further.";
            case BLINK -> "Teleport toward your aim.";
            case WARD -> "Raise a defensive veil.";
            case DAGGERS -> "Conjure a dagger; again: put it away.";
            case TWIN_DAGGERS -> "Conjure The Deceiver. Hold use: guard and parry.";
            case LAEVATEINN -> "Conjure/recall the Scepter. Hold right click 1-10s: beam.";
            case ENCHANT -> "Charm a creature to follow you.";
            case MEMORY -> "Reveal a target's recent steps.";
            case TIME_SLIP -> "Slip to a recent moment.";
            case REWIND -> "Rewind position and some health.";
            case SLOW_FIELD -> "";
            case TIME_STOP -> "Freeze the local battlefield.";
            case SELECTIVE_STOP -> "Freeze the target in your aim.";
            case THREADS -> "Tap: vanish. Hold: Gravity Grasp.";
            case ASCENSION -> "Toggle your final transformation.";
            case TIME_BRANCH -> "Tap: right fist. Hold/release: beam.";
            case ARSENAL -> "Tap: Gotcha! Hold: the crown (13s: missiles).";
        };
    }
}
