#version 150
// Gravity Grasp's cone of bent air: a plain shell round the cone, drawn only to find the pixels it covers. Where each
// pixel's ray passes through the cone, and how deep, is worked out in the fragment shader from the cone itself.
in vec3 Position;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec3 viewPos;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    viewPos = view.xyz;
    gl_Position = ProjMat * view;
}
