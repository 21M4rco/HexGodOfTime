#version 150
// Gravity Grasp, seen: the air itself, bent toward the palm. No light and nothing solid: what is drawn is the world
// behind (Sampler0, a copy of the frame taken just before), redrawn as though dragged in toward the hand.
//
// The cone runs from the palm (Apex) along Axis for Length, its radius growing from Radii.x at the palm to Radii.y at
// the far end, everything in view space. For each pixel, the place its ray passes nearest the cone's axis says how deep
// into the cone it looks (the middle bends most, the edges not at all) and how far out from the palm (the swells of air
// roll in along it to the hand, and the space round the palm itself is pinched into it). The bend is made there, in the
// world, and carried to the screen through the same projection, so it reads the same from any side: first person, from
// behind, from in front.
uniform sampler2D Sampler0;
uniform mat4 ProjMat;
uniform vec2 ScreenSize;
uniform vec3 Apex;
uniform vec3 Axis;
uniform float Length;
uniform vec2 Radii;
uniform float Strength;
uniform float Haul;
uniform float Time;
in vec3 viewPos;
out vec4 fragColor;

vec2 screenOf(vec3 v) {
    vec4 c = ProjMat * vec4(v, 1.0);
    return c.xy / max(c.w, 1e-4) * .5 + .5;
}

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec3 ray = normalize(viewPos);
    // Nearest approach of the ray (from the eye, at the origin) to the axis, kept to the cone's own length.
    vec3 w0 = -Apex;
    float b = dot(ray, Axis), d = dot(ray, w0), e = dot(Axis, w0);
    float denom = 1.0 - b * b;
    float u = denom > 1e-5 ? (e - b * d) / denom : Length * .5;
    u = clamp(u, 0.0, Length);
    vec3 q = Apex + Axis * u;
    float s = max(0.0, dot(ray, q));
    vec3 p = ray * s;
    float dist = length(p - q);
    float t = u / max(Length, 1e-3);
    float radius = mix(Radii.x, Radii.y, pow(t, .85));
    float inside = clamp(1.0 - dist / radius, 0.0, 1.0);
    float soft = inside * inside * (3.0 - 2.0 * inside);
    // Gone toward the far end, and never right against the eye (a cone pointed at the viewer would fill the view).
    soft *= 1.0 - smoothstep(.8, 1.0, t);
    soft *= smoothstep(.25, 1.1, s);
    if (soft <= 0.0) {fragColor = vec4(texture(Sampler0, uv).rgb, 1.0); return;}

    // Swells of air rolling in to the palm: a crest at u*K + Time*V moves toward the palm as time goes on.
    float speed = mix(5.0, 14.0, Haul);
    float wave = sin(u * 5.5 + Time * speed);
    float wave2 = sin(u * 2.3 + Time * speed * .55 + 1.3);
    // Close to the eye the same bend in the world is a far bigger one on the screen: a hand's length from the camera it
    // is kept to a third, so the palm in first person draws the air in without tearing the view open.
    float power = Strength * mix(1.0, 1.7, Haul) * mix(.33, 1.0, smoothstep(.6, 3.5, s));
    // A wide cone is a wide pull: the bend grows with the cone's girth where the ray crosses it, so its far end rolls in
    // great waves rather than a ripple.
    float girth = .5 + .35 * clamp(radius, .5, 3.6);
    // Dragged in toward the palm: what is drawn here is what lies further out along the cone.
    vec3 bend = Axis * soft * (.15 + .13 * wave + .06 * wave2) * power * girth;
    // Squeezed in toward the axis: what is drawn here lies further off it.
    vec3 off = p - q;
    bend += off * soft * (.40 + .16 * wave) * power;
    // The palm: the space round it pinched in, drawn from further out, nothing at the palm itself and most a hand's
    // breadth off it, so it closes smoothly on the hand rather than bursting out of a point.
    vec3 fromPalm = p - Apex;
    float pinch = exp(-dot(fromPalm, fromPalm) / .5) * soft;
    bend += fromPalm * pinch * .45 * power;

    vec2 delta = screenOf(p + bend) - screenOf(p);
    vec3 rgb;
    // Light bent by it splits a hair, as through thick glass, most where it bends most.
    float split = 1.0 + .18 * soft;
    rgb.r = texture(Sampler0, clamp(uv + delta * split, vec2(.001), vec2(.999))).r;
    rgb.g = texture(Sampler0, clamp(uv + delta, vec2(.001), vec2(.999))).g;
    rgb.b = texture(Sampler0, clamp(uv + delta * (2.0 - split), vec2(.001), vec2(.999))).b;
    // Dense air: a little of the colour and light pressed out of its heart, the crests catching what is left, and the
    // edge of the bend just visible as heat haze is.
    float gray = dot(rgb, vec3(.2126, .7152, .0722));
    rgb = mix(rgb, vec3(gray) * .9, soft * soft * Strength * .2);
    rgb *= 1.0 + max(0.0, wave) * soft * Strength * .13;
    float rim = smoothstep(.0, .25, inside) * (1.0 - smoothstep(.25, .55, inside));
    rgb += vec3(.035, .03, .045) * rim * Strength;
    fragColor = vec4(rgb, 1.0);
}
