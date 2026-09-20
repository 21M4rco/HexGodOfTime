package com.loki.server;

import java.util.*;

/** Run with Java assertions enabled. Tests the player-facing topology, not copies of the formula. */
public final class RealmShapeTest {
    public static void main(String[] args) {
        int area=0,minDepth=999,maxDepth=0,minEdge=999,maxEdge=0;
        Set<Long> land=new HashSet<>();
        for(int x=RealmShape.MIN;x<=RealmShape.MAX;x++)for(int z=RealmShape.MIN;z<=RealmShape.MAX;z++) {
            if(!RealmShape.contains(x,z))continue;
            assert x>RealmShape.MIN&&x<RealmShape.MAX&&z>RealmShape.MIN&&z<RealmShape.MAX : "Island clipped by build bounds";
            area++;land.add(key(x,z));
            int top=RealmShape.surface(x,z),depth=RealmShape.depth(x,z);
            assert top>=0&&top<=RealmShape.TOP : "Surface outside generated layers";
            assert depth>=3&&depth<=RealmShape.DEPTH&&64-depth>0 : "Underside outside dimension";
            minDepth=Math.min(minDepth,depth);maxDepth=Math.max(maxDepth,depth);
        }
        assert area>2*Math.PI*47*47 : "Island must offer over twice the old usable footprint";
        assert maxDepth-minDepth>30 : "Underside must taper substantially";
        for(int degrees=0;degrees<360;degrees++) {
            int edge=(int)RealmShape.edge(Math.toRadians(degrees));minEdge=Math.min(minEdge,edge);maxEdge=Math.max(maxEdge,edge);
        }
        assert maxEdge-minEdge>=15 : "Shoreline must not form a cylinder";
        for(int x=42;x<=58;x++)for(int z=44;z<=76;z++) {
            assert RealmShape.contains(x,z)&&RealmShape.surface(x,z)==0 : "Throne/return approach must stay flat and connected";
        }
        ArrayDeque<int[]> queue=new ArrayDeque<>();queue.add(new int[]{50,50});land.remove(key(50,50));
        while(!queue.isEmpty()) {
            int[] p=queue.remove();
            for(int[] step:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int x=p[0]+step[0],z=p[1]+step[1];if(land.remove(key(x,z)))queue.add(new int[]{x,z});
            }
        }
        assert land.isEmpty() : "Walkable island contains disconnected fragments";
        System.out.println("Island topology passed: "+area+" columns; depth "+minDepth+".."+maxDepth+"; coast radius "+minEdge+".."+maxEdge);
    }
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
}
