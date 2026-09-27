#version 150

// The Crown of Barrels' guns, rocket and casings: TACZ's models, lit as vanilla lights an entity, with the light of
// the world where they hang, and measured along their length for the forming edge (arsenal_gun.fsh).

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// Back from the view's turn to the world's own, and the shape of the world's fog: its distance is measured as
// vanilla measures an entity's (fog.glsl's fog_distance), level for terrain fog and spherical otherwise.
uniform mat3 IViewRotMat;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
// Block and sky light where the crown hangs, as a lightmap coordinate; a part lit from within carries its own.
uniform vec2 WorldLight;
// Where the model's back and its muzzle lie along its length (-z), so the forming edge can run from one to the other.
uniform vec2 RevealSpan;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out float along;
out vec3 cell;
out float rim;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    vec3 world = IViewRotMat * view.xyz;
    vertexDistance = FogShape == 0 ? length(world) : max(length(world.xz), abs(world.y));

    // Vanilla's two entity lights, with the normal carried into view space here since the model is drawn from a
    // buffer that never moves.
    vec3 normal = normalize(mat3(ModelViewMat) * Normal);
    float light0 = max(0.0, dot(normalize(Light0_Direction), normal));
    float light1 = max(0.0, dot(normalize(Light1_Direction), normal));
    float shade = min(1.0, (light0 + light1) * 0.6 + 0.4);
    ivec2 lightUV = max(UV2, ivec2(WorldLight));
    vec4 light = texelFetch(Sampler2, clamp(lightUV / 16, ivec2(0), ivec2(15)), 0);
    vertexColor = vec4(Color.rgb * shade, Color.a) * light;

    texCoord0 = UV0;
    along = (-Position.z - RevealSpan.x) / max(RevealSpan.y - RevealSpan.x, 0.0001);
    cell = Position;
    rim = 1.0 - abs(dot(normal, normalize(-view.xyz)));
}
