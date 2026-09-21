package com.hexgodofstories.client;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Small immediate mesh vocabulary; all surfaces have volume and never use debug line rendering. */
public final class WarpMesh {
    public static void vertex(BufferBuilder b,Matrix4f m,Vec3 p,int color,float a){b.vertex(m,(float)p.x,(float)p.y,(float)p.z).color(((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,a).endVertex();}
    public static void quad(BufferBuilder b,Matrix4f m,Vec3 a,Vec3 c,Vec3 d,Vec3 e,int color,float alpha){vertex(b,m,a,color,alpha);vertex(b,m,c,color,alpha);vertex(b,m,d,color,alpha);vertex(b,m,e,color,alpha);}
    public static void box(BufferBuilder b,Matrix4f m,double x,double y,double z,double w,double h,double depth,int color,float a){
        Vec3[] p={new Vec3(x,y,z),new Vec3(x+w,y,z),new Vec3(x+w,y+h,z),new Vec3(x,y+h,z),new Vec3(x,y,z+depth),new Vec3(x+w,y,z+depth),new Vec3(x+w,y+h,z+depth),new Vec3(x,y+h,z+depth)};
        int[][] faces={{0,1,2,3},{5,4,7,6},{4,0,3,7},{1,5,6,2},{3,2,6,7},{4,5,1,0}};
        for(int i=0;i<faces.length;i++){int[] f=faces[i];quad(b,m,p[f[0]],p[f[1]],p[f[2]],p[f[3]],shade(color,i==4?1:i==5?.45:.72+i*.03),a);}
    }
    public static int shade(int color,double factor){return (Math.min(255,(int)((color>>16&255)*factor))<<16)|(Math.min(255,(int)((color>>8&255)*factor))<<8)|Math.min(255,(int)((color&255)*factor));}
    public static void sphere(BufferBuilder b,Matrix4f m,Vec3 c,double rx,double ry,double rz,int color,float alpha,int resolution,double time,boolean plasma){
        for(int y=0;y<resolution/2;y++)for(int x=0;x<resolution;x++){
            for(int[] corner:new int[][]{{0,0},{1,0},{1,1},{0,1}}){
                double lon=(x+corner[0])*Math.PI*2/resolution,lat=(y+corner[1])*Math.PI*2/resolution;
                double nx=Math.sin(lat)*Math.cos(lon),ny=Math.cos(lat),nz=Math.sin(lat)*Math.sin(lon);
                int col=color;if(plasma){double f=RealmSky.fbm(nx*9+time*.006,ny*9,nz*9);col=f>.57?0xffecac:f>.42?0xffb437:0xe85b13;}
                vertex(b,m,c.add(nx*rx,ny*ry,nz*rz),col,alpha);
            }
        }
    }
    public static void ribbon(BufferBuilder b,Matrix4f m,Vec3 a,Vec3 c,double width,int color,float alpha){Vec3 side=c.subtract(a).cross(new Vec3(.2,1,.1)).normalize().scale(width);quad(b,m,a.subtract(side),a.add(side),c.add(side),c.subtract(side),color,alpha);}
    public static void ring(BufferBuilder b,Matrix4f m,Vec3 c,double radius,double width,int color,float alpha,double tilt,double time){
        for(int i=0;i<128;i++){double a=i*Math.PI/64+time,t=(i+1)*Math.PI/64+time;quad(b,m,ringPoint(c,a,radius,tilt),ringPoint(c,t,radius,tilt),ringPoint(c,t,radius+width,tilt),ringPoint(c,a,radius+width,tilt),shade(color,.65+.35*Math.sin(a*3-time*4)),alpha);}
    }
    private static Vec3 ringPoint(Vec3 c,double a,double r,double tilt){return c.add(Math.cos(a)*r,Math.sin(a)*r*Math.sin(tilt),Math.sin(a)*r*Math.cos(tilt));}
}
