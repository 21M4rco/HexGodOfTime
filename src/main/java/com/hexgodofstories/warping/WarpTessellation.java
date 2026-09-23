package com.hexgodofstories.warping;

import java.util.ArrayList;
import java.util.List;

/** Clips a convex pool sector to one horizontal floor rectangle. No sloping terrain faces. */
public final class WarpTessellation {
    private WarpTessellation() {}
    public record Point(double x,double z) {}

    public static List<Point> clip(double ax,double az,double bx,double bz,
                                   double minX,double minZ,double maxX,double maxZ) {
        List<Point> p=new ArrayList<>(List.of(new Point(0,0),new Point(ax,az),new Point(bx,bz)));
        p=cut(p,0,minX,true);p=cut(p,0,maxX,false);
        p=cut(p,1,minZ,true);return cut(p,1,maxZ,false);
    }
    private static List<Point> cut(List<Point> input,int axis,double edge,boolean greater) {
        List<Point> out=new ArrayList<>();
        if(input.isEmpty())return out;
        Point a=input.get(input.size()-1);
        double av=axis==0?a.x:a.z;
        boolean ain=greater?av>=edge:av<=edge;
        for(Point b:input){
            double bv=axis==0?b.x:b.z;
            boolean bin=greater?bv>=edge:bv<=edge;
            if(ain!=bin){
                double t=(edge-av)/(bv-av);
                out.add(new Point(a.x+(b.x-a.x)*t,a.z+(b.z-a.z)*t));
            }
            if(bin)out.add(b);
            a=b;av=bv;ain=bin;
        }
        return out;
    }
    public static double area(List<Point> polygon){
        double area=0;
        for(int i=0;i<polygon.size();i++){
            Point a=polygon.get(i),b=polygon.get((i+1)%polygon.size());
            area+=a.x*b.z-b.x*a.z;
        }
        return Math.abs(area)*.5;
    }
}
