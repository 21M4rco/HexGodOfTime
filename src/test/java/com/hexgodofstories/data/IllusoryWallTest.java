package com.hexgodofstories.data;

/**
 * Run with Java assertions enabled. Covers the size table and the quarter-turn facing — the parts a
 * player can feel — without touching the block palette, which needs a running game to speak for it.
 */
public final class IllusoryWallTest {
    public static void main(String[] args) {
        assert IllusoryWall.SIZES==4 : "Small, Medium, Big, Massive";
        int previousWidth=0,previousHeight=0;
        for(int size=1;size<=IllusoryWall.SIZES;size++) {
            int width=IllusoryWall.width(size),height=IllusoryWall.height(size);
            assert width%2==1 : "A wall must be centred on the column that was aimed at";
            assert width>previousWidth : "Each size must be wider than the last";
            assert height>previousHeight : "Each size must be taller than the last";
            previousWidth=width;previousHeight=height;
        }
        assert IllusoryWall.name(1).equals("Small")&&IllusoryWall.name(4).equals("Massive") : "Sizes are named for the player";
        assert IllusoryWall.clamp(0)==1&&IllusoryWall.clamp(99)==IllusoryWall.SIZES : "Sizes clamp rather than wrap";
        assert IllusoryWall.name(0).equals("Small")&&IllusoryWall.name(9).equals("Massive") : "Naming clamps with the size";

        for(int degrees=-720;degrees<=720;degrees++) {
            int facing=IllusoryWall.facing(degrees);
            assert facing>=0&&facing<4 : "Facing must be one of four quarter turns, at any yaw";
        }
        // The wall stands across the caster's view, so opposite headings share an axis and
        // perpendicular ones do not. Without this a wall could be raised edge-on and hide nothing.
        assert IllusoryWall.facing(0)%2==IllusoryWall.facing(180)%2 : "Opposite headings share an axis";
        assert IllusoryWall.facing(90)%2==IllusoryWall.facing(270)%2 : "Opposite headings share an axis";
        assert IllusoryWall.facing(0)%2!=IllusoryWall.facing(90)%2 : "A quarter turn must change the axis";
        assert IllusoryWall.facing(-90)==IllusoryWall.facing(270) : "Negative yaw is the same heading";

        System.out.println("Illusory wall sizes passed: "
            +IllusoryWall.width(1)+"x"+IllusoryWall.height(1)+" up to "
            +IllusoryWall.width(IllusoryWall.SIZES)+"x"+IllusoryWall.height(IllusoryWall.SIZES));
    }
}
