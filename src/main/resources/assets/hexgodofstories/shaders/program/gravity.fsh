#version 150
// Gravity Grasp, seen: no light, no particle, nothing solid. The air itself between a pointing hand and what it means to
// pull bends toward the hand. A corridor of refraction runs from the source (a point out along the look while the hand
// points; the body itself once it is hauled) to the palm. Within it the world behind is drawn as though dragged
// toward the hand and squeezed in toward the corridor's line, with slow swells of it streaming in along the line, and
// the space right round the palm is pinched into it. Hauling, all of it is stronger and quicker, and the space round
// the body ripples in toward the hand. A hair of colour separation where it is strongest is light bent by it.
uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform float Strength;
uniform float Phase;
uniform float Haul;
uniform vec2 Sink;
uniform vec2 Source;
uniform vec2 Widths;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    vec2 scale = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = uv * scale, k = Sink * scale, s = Source * scale;
    vec2 axis = s - k;
    float len = max(length(axis), 1e-4);
    vec2 dir = axis / len;
    vec2 side = vec2(-dir.y, dir.x);
    // Where along the corridor this is (0 at the palm, 1 at the source), and how far off its line.
    float t = clamp(dot(p - k, dir) / len, 0.0, 1.0);
    vec2 across = p - (k + dir * t * len);
    float d = length(across);
    float width = mix(Widths.x, Widths.y, t);
    float g = exp(-(d * d) / (width * width));
    // The palm, and which way from it this pixel lies.
    float r = length(p - k);
    vec2 radial = r > 1e-5 ? (p - k) / r : vec2(0.0);
    // Swells of air rolling in to the palm: rings round it that tighten as they come (a crest at r*F + Phase*V moves
    // inward as time goes on), seen only within the corridor, so they read as a stream rather than as a shockwave.
    float speed = mix(40.0, 120.0, Haul);
    float wave = sin(r * 150.0 + Phase * speed);
    float wave2 = sin(r * 61.0 + Phase * speed * .55 + 1.3);
    vec2 warp = vec2(0.0);
    // Dragged in toward the palm: what is drawn here is what lies a little further out along the way it comes.
    warp += radial * g * (.011 + .008 * wave + .004 * wave2) * mix(.8, 1.7, Haul);
    // Squeezed in toward the line: what is drawn here lies a little further off it.
    vec2 off = d > 1e-5 ? across / d : vec2(0.0);
    warp += off * g * min(d / width, 1.5) * .011 * mix(.8, 1.4, Haul);
    // The shimmer of it, across the line.
    warp += side * g * sin(t * len * 120.0 + Phase * speed * .9) * .0022;
    // The palm: the space round it pinched in, drawn from further out.
    float pinch = exp(-(r * r) / pow(Widths.x * 2.4, 2.0));
    warp += (p - k) * pinch * .32;
    // Hauling: the space round the body rippling in toward the hand.
    float rs = length(p - s);
    float hold = exp(-(rs * rs) / pow(Widths.y * 1.9, 2.0)) * Haul;
    warp += dir * hold * (.012 + .009 * sin(rs * 95.0 - Phase * 16.0));

    vec2 shift = warp * Strength / scale;
    vec2 at = clamp(uv + shift, vec2(.001), vec2(.999));
    vec3 rgb = texture(DiffuseSampler, at).rgb;
    float bent = clamp((g + pinch + hold) * Strength, 0.0, 1.0);
    // Light bent by it splits a hair at the corridor's edges and on the swells, as through thick glass.
    float rim = g * smoothstep(.25, .9, d / width) + abs(wave) * g * .5 + pinch * .4;
    float split = 1.0 + .16 * rim;
    rgb.r = texture(DiffuseSampler, clamp(uv + shift * split, vec2(.001), vec2(.999))).r;
    rgb.b = texture(DiffuseSampler, clamp(uv + shift * (2.0 - split), vec2(.001), vec2(.999))).b;
    // Dense air: a little of the colour and light pressed out of it along the line, and the crests catching what is left.
    float gray = dot(rgb, vec3(.2126, .7152, .0722));
    float dense = g * exp(-(d * d) / (width * width * .3)) * Strength;
    rgb = mix(rgb, vec3(gray) * .93, dense * .22);
    rgb *= 1.0 + max(0.0, wave) * g * Strength * .07;
    fragColor = vec4(mix(texture(DiffuseSampler, uv).rgb, rgb, smoothstep(0.0, .02, bent)), 1.0);
}
