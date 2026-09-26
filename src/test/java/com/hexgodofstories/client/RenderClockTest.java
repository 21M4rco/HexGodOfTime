package com.hexgodofstories.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Effects are timed from the world's game time, and a server that runs day and night takes that past
 * what a float can hold: 2^23 ticks is under five days. A partial tick added to it in float, or a
 * palette phase narrowed from it, makes everything timed that way start late and move in jumps on a
 * long-running server while a fresh single-player world looks perfect. No Minecraft bootstrap needed.
 */
public final class RenderClockTest {
    /** The long clock plus a float partial tick: Java does that sum in float. Use ClientState.time or since. */
    private static final Pattern FLOAT_SUM=Pattern.compile(
        "(now\\(\\)|getGameTime\\(\\))\\s*\\+\\s*(\\(float\\)\\s*)?[A-Za-z_.]*(partial[A-Za-z]*|PartialTick\\(\\)|FrameTime\\(\\))");
    /** A palette phase narrowed to float before its whole turns are dropped. Use ClientState.cycle. */
    private static final Pattern FLOAT_PHASE=Pattern.compile("Palette\\.\\w+\\(\\s*\\(float\\)");

    public static void main(String[] args) throws Exception {
        theHelpersStayExactOnAnOldWorld();
        noRendererNarrowsTheClock(projectRoot());
        System.out.println("RenderClockTest: effects keep the same timing on a world that has run for months.");
    }

    /** The arithmetic behind ClientState.since and ClientState.time, at a game time a server reaches in three weeks. */
    private static void theHelpersStayExactOnAnOldWorld() {
        long now=(1L<<25)+3,stamp=now-1;
        float partial=.5f;
        require(now+partial-stamp!=1.5f,"float kept the partial tick; the premise of this test has changed");
        require((now-stamp)+partial==1.5f,"a difference taken in whole ticks first is no longer exact");
        require(now+(double)partial-stamp==1.5,"the double render clock is no longer exact");
    }

    private static void noRendererNarrowsTheClock(Path root) throws Exception {
        List<String> found=new ArrayList<>();
        try(Stream<Path> files=Files.walk(root.resolve("src/main/java/com/hexgodofstories"))) {
            for(Path file:files.filter(f->f.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines=Files.readAllLines(file);
                for(int i=0;i<lines.size();i++) {
                    String line=lines.get(i).trim();
                    if(line.startsWith("*")||line.startsWith("/*")||line.startsWith("//"))continue;
                    if(FLOAT_SUM.matcher(line).find()||FLOAT_PHASE.matcher(line).find())
                        found.add(root.relativize(file)+":"+(i+1)+": "+line);
                }
            }
        }
        require(found.isEmpty(),"the game-time clock is narrowed to float here:\n  "+String.join("\n  ",found));
    }

    private static Path projectRoot() {
        Path here=Path.of("").toAbsolutePath();
        for(int i=0;i<4&&here!=null;i++) {
            if(Files.isDirectory(here.resolve("src/main/java/com/hexgodofstories/client")))return here;
            here=here.getParent();
        }
        throw new IllegalStateException("run this from the project root");
    }

    private static void require(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
}
