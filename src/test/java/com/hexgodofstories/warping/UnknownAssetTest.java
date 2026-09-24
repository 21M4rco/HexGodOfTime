package com.hexgodofstories.warping;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Asset regression: runs on ordinary Gradle CI without a Minecraft client. */
public final class UnknownAssetTest {
    private static JsonObject load(String suffix) throws Exception {
        Path file=Path.of("src/main/resources/assets/hexgodofstories/"+suffix);
        if(!Files.isRegularFile(file))throw new AssertionError("Missing "+file);
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }
    private static void require(boolean pass,String message) {if(!pass)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        var geometry=load("geo/unknown.geo.json").getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        var bones=geometry.getAsJsonArray("bones");
        Set<String> names=new HashSet<>();int cubes=0;
        for(var element:bones) {
            var bone=element.getAsJsonObject();
            if(bone.has("parent"))require(names.contains(bone.get("parent").getAsString()),"Bone parent must appear before its child");
            names.add(bone.get("name").getAsString());
            if(bone.has("cubes"))cubes+=bone.getAsJsonArray("cubes").size();
        }
        require(bones.size()==29&&cubes==695,"Reference geometry changed");
        require(names.contains("arm_left_hand")&&names.contains("arm_right_hand"),"Two articulated arms required");
        require(names.contains("lower_jaw")&&names.contains("head"),"Skull and independently animated jaws required");
        require(names.contains("tail_13")&&names.contains("abdomen"),"Dragging articulated abdomen/tail required");
        for(String name:names)require(!name.contains("leg_"),"No rear legs are allowed");
        var clips=load("animations/unknown.animation.json").getAsJsonObject("animations");
        require(clips.size()==9,"All nine animations must ship");
        for(String clip:new String[]{"spawn","idle","walk","run","bite","jump","swim","roar","death"})
            require(clips.has("animation.unknown."+clip),"Missing animation "+clip);
        var spawn=clips.getAsJsonObject("animation.unknown.spawn");
        require(Math.abs(spawn.get("animation_length").getAsDouble()-10)<.01,"Emergence is ten seconds");
        require(!spawn.get("loop").getAsBoolean(),"Emergence must not repeat");
        var chase=clips.getAsJsonObject("animation.unknown.run");
        require(chase.getAsJsonObject("bones").has("lower_jaw"),"Chase mouth must be open");
        require(chase.get("loop").getAsBoolean(),"Chase animation must loop");
        var bite=clips.getAsJsonObject("animation.unknown.bite");
        require(bite.getAsJsonObject("bones").getAsJsonObject("root").has("position"),"Bite must include a physical lunge");
        require(!bite.get("loop").getAsBoolean(),"Bite must not repeat forever");
        require(Files.isRegularFile(Path.of("src/main/resources/assets/hexgodofstories/textures/entity/unknown.png")),"Missing original texture");
        require(Files.isRegularFile(Path.of("tools/unknown/abyssal_pilgrim.bbmodel")),"Missing editable source");
        System.out.println("Unknown asset regression: 29 bones, 695 cubes and nine playable clips verified.");
    }
}
