#version 150

// A gun forms from its back to its muzzle, whole model-pixels at a time, behind a burning edge; it comes apart the
// same way backward. Formed, it carries a faint glow along its silhouette, and flares with its own muzzle flash.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// How much of it has formed, 0 to 1.
uniform float Reveal;
// The forming edge's light, the glow along its silhouette (and how strong), and its own muzzle flash, 0 to 1.
uniform vec3 EdgeColor;
uniform vec4 RimColor;
uniform float Flash;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in float along;
in vec3 cell;
in float rim;

out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    // The edge runs a little past both ends, so nothing formed is cut and nothing unformed shows.
    float front = Reveal * 1.3 - 0.12;
    float t = along + (hash(floor(cell * 16.0 + 0.5)) - 0.5) * 0.16;
    if (t > front) {
        discard;
    }
    float edge = 1.0 - smoothstep(0.0, 0.09, front - t);

    vec4 color = tex * vertexColor * ColorModulator;
    color.rgb += tex.rgb * vec3(1.0, 0.62, 0.3) * Flash;
    color.rgb += RimColor.rgb * pow(rim, 3.0) * RimColor.a;
    color.rgb = mix(color.rgb, EdgeColor, edge * 0.8) + EdgeColor * edge * 0.9;
    // And white-hot at its very front.
    color.rgb += vec3(1.0, 0.86, 0.62) * pow(edge, 6.0) * 0.8;

    // Vanilla's linear fog.
    if (vertexDistance > FogStart) {
        float fog = vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0;
        color.rgb = mix(color.rgb, FogColor.rgb, fog * FogColor.a);
    }
    fragColor = vec4(color.rgb, 1.0);
}
