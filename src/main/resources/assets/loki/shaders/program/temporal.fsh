#version 150
uniform sampler2D DiffuseSampler;
uniform float Strength;
uniform float Phase;
uniform float Mode;
in vec2 texCoord;
out vec4 fragColor;
float hash(float p) { return fract(sin(p*127.1)*43758.5453); }
float segment(vec2 p,vec2 a,vec2 b) { vec2 d=b-a;return length(p-a-d*clamp(dot(p-a,d)/dot(d,d),0.0,1.0)); }
void main() {
 vec2 uv=texCoord;vec2 c=uv-vec2(.5);float r=length(c);float a=atan(c.y,c.x);
 float onset=exp(-Phase*1.8);float fracture=0.0;vec2 warp=vec2(0.0);
 for(int i=0;i<12;i++) {
  float k=float(i);float angle=k*6.283185/12.0+hash(k)*.23;
  vec2 ray=vec2(cos(angle),sin(angle));vec2 bend=ray*(.15+hash(k+8.0)*.22);
  float d=min(segment(c,ray*.065,bend),segment(c,bend,ray*.85+vec2(hash(k+9.0)-.5)*.1));
  float edge=1.0-smoothstep(.0006,.0021,d);
  fracture=max(fracture,edge);
  warp+=ray*(1.0-smoothstep(.0,.015,d))*.004*onset;
 }
 float pulse=sin(r*45.0-Phase*10.0)*exp(-pow(r-Phase*.5,2.0)*90.0)*.012;
 float slip=Mode==2.0?1.0:0.0;
 warp+=c*pulse*onset+vec2(sin(uv.y*31.0+Phase*28.0)*.007,0.0)*slip;
 vec2 at=clamp(uv+warp*Strength,vec2(.002),vec2(.998));
 vec3 rgb=texture(DiffuseSampler,at).rgb;
 float split=Strength*(.0015+.004*onset+slip*.008);
 rgb.r=texture(DiffuseSampler,clamp(at+c*split,0.001,0.999)).r;
 rgb.b=texture(DiffuseSampler,clamp(at-c*split,0.001,0.999)).b;
 float gray=dot(rgb,vec3(.2126,.7152,.0722));
 float stop=(Mode==1.0||Mode==4.0)?1.0:0.0;
 rgb=mix(rgb,vec3(gray)*vec3(.94,.99,.96),Strength*.92*stop);
 float threads=pow(max(0.0,sin(a*18.0+r*15.0-Phase*1.2)),70.0)*smoothstep(.18,.7,r);
 rgb+=vec3(.3,.4,.19)*threads*Strength*(Mode==3.0?.45:.07);
 rgb+=fracture*vec3(.69,.72,.58)*Strength*onset*.7*stop;
 rgb*=1.0-Strength*smoothstep(.24,.75,r)*.28;
 fragColor=vec4(rgb,1.0);
}
