package com.hexgodofstories.warping;

/** Area conservation across fractional block boundaries catches the missing-floor regression. */
public final class WarpTessellationTest {
    public static void main(String[] args){
        for(int seed=0;seed<24;seed++)for(double progress:new double[]{.05,.4,1}){
            double[] rim=WarpPool.rim(seed,14,progress);
            double expected=0,covered=0;
            for(int i=0;i<rim.length;i++){
                int j=(i+1)%rim.length;
                double ax=Math.cos(i*Math.PI*2/rim.length)*rim[i],az=Math.sin(i*Math.PI*2/rim.length)*rim[i];
                double bx=Math.cos(j*Math.PI*2/rim.length)*rim[j],bz=Math.sin(j*Math.PI*2/rim.length)*rim[j];
                expected+=Math.abs(ax*bz-bx*az)*.5;
                int minX=(int)Math.floor(Math.min(0,Math.min(ax,bx))-.37);
                int maxX=(int)Math.ceil(Math.max(0,Math.max(ax,bx))-.37);
                int minZ=(int)Math.floor(Math.min(0,Math.min(az,bz))-.19);
                int maxZ=(int)Math.ceil(Math.max(0,Math.max(az,bz))-.19);
                for(int x=minX;x<maxX;x++)for(int z=minZ;z<maxZ;z++){
                    var p=WarpTessellation.clip(ax,az,bx,bz,x+.37,z+.19,x+1.37,z+1.19);
                    for(var v:p)check(Double.isFinite(v.x())&&Double.isFinite(v.z())
                        &&v.x()>=x+.37-1E-9&&v.x()<=x+1.37+1E-9
                        &&v.z()>=z+.19-1E-9&&v.z()<=z+1.19+1E-9,"vertex stays on its floor tile");
                    covered+=WarpTessellation.area(p);
                }
            }
            check(Math.abs(expected-covered)<1E-7,"no holes or overlap: "+expected+" / "+covered);
        }
        check(WarpTessellation.area(WarpTessellation.clip(0,0,0,0,-1,-1,1,1))==0,"closed portal is empty");
        check(WarpTessellation.clip(1,0,0,1,5,5,6,6).isEmpty(),"outside tile is empty");
        System.out.println("Liquid portal area conservation passed");
    }
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
}
