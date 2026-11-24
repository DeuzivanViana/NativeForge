// A lit, spinning cube with OpenGL ES 3 shaders, SDL3 for the window and GLM for the
// matrix math (glm::perspective, glm::lookAt, glm::rotate work as in desktop OpenGL).
// Drag to rotate it; it keeps spinning with the speed you gave it.
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <GLES3/gl3.h>

#include <glm/glm.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>

namespace {

const char* kVertexShader = R"(#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
uniform mat4 uModel;
uniform mat4 uViewProjection;
out vec3 vNormal;
out vec3 vColor;
void main() {
    vNormal = mat3(uModel) * aNormal;
    vColor = aColor;
    gl_Position = uViewProjection * uModel * vec4(aPosition, 1.0);
}
)";

const char* kFragmentShader = R"(#version 300 es
precision mediump float;
in vec3 vNormal;
in vec3 vColor;
out vec4 fragColor;
void main() {
    vec3 light = normalize(vec3(0.4, 0.8, 0.6));
    float diffuse = max(dot(normalize(vNormal), light), 0.0);
    fragColor = vec4(vColor * (0.25 + 0.75 * diffuse), 1.0);
}
)";

GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(shader, sizeof log, nullptr, log);
        SDL_Log("Shader error: %s", log);
    }
    return shader;
}

// 6 faces x 4 corners: position, normal, colour. Each face has its own colour.
void buildCube(GLfloat* vertices, GLushort* indices) {
    const glm::vec3 normals[6] = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
    const glm::vec3 colors[6] = {{0.90f, 0.35f, 0.35f}, {0.35f, 0.75f, 0.45f}, {0.35f, 0.55f, 0.95f},
                                 {0.95f, 0.80f, 0.30f}, {0.75f, 0.45f, 0.90f}, {0.30f, 0.85f, 0.85f}};
    for (int face = 0; face < 6; ++face) {
        const glm::vec3 n = normals[face];
        // Two axes perpendicular to the normal span the face.
        const glm::vec3 u = glm::abs(n.y) > 0.5f ? glm::vec3(1, 0, 0) : glm::normalize(glm::cross(glm::vec3(0, 1, 0), n));
        const glm::vec3 v = glm::cross(n, u);
        const glm::vec2 corners[4] = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int c = 0; c < 4; ++c) {
            const glm::vec3 p = (n + u * corners[c].x + v * corners[c].y) * 0.5f;
            GLfloat* out = vertices + (face * 4 + c) * 9;
            out[0] = p.x; out[1] = p.y; out[2] = p.z;
            out[3] = n.x; out[4] = n.y; out[5] = n.z;
            out[6] = colors[face].r; out[7] = colors[face].g; out[8] = colors[face].b;
        }
        const GLushort base = static_cast<GLushort>(face * 4);
        const GLushort quad[6] = {base, static_cast<GLushort>(base + 1), static_cast<GLushort>(base + 2),
                                  base, static_cast<GLushort>(base + 2), static_cast<GLushort>(base + 3)};
        for (int i = 0; i < 6; ++i) indices[face * 6 + i] = quad[i];
    }
}

} // namespace

int main(int, char*[]) {
    if (!SDL_Init(SDL_INIT_VIDEO)) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
    SDL_Window* window = SDL_CreateWindow("@PROJECT_NAME@", 0, 0,
                                          SDL_WINDOW_OPENGL | SDL_WINDOW_FULLSCREEN | SDL_WINDOW_HIGH_PIXEL_DENSITY);
    SDL_GLContext context = window ? SDL_GL_CreateContext(window) : nullptr;
    if (context == nullptr) {
        SDL_Log("Could not create an OpenGL ES 3 context: %s", SDL_GetError());
        return 1;
    }
    SDL_GL_SetSwapInterval(1);

    GLuint program = glCreateProgram();
    glAttachShader(program, compile(GL_VERTEX_SHADER, kVertexShader));
    glAttachShader(program, compile(GL_FRAGMENT_SHADER, kFragmentShader));
    glLinkProgram(program);
    const GLint modelLocation = glGetUniformLocation(program, "uModel");
    const GLint viewProjectionLocation = glGetUniformLocation(program, "uViewProjection");

    GLfloat vertices[24 * 9];
    GLushort indices[36];
    buildCube(vertices, indices);
    GLuint vao = 0, buffers[2] = {0, 0};
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    glGenBuffers(2, buffers);
    glBindBuffer(GL_ARRAY_BUFFER, buffers[0]);
    glBufferData(GL_ARRAY_BUFFER, sizeof vertices, vertices, GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[1]);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof indices, indices, GL_STATIC_DRAW);
    for (GLuint attribute = 0; attribute < 3; ++attribute) {
        glEnableVertexAttribArray(attribute);
        glVertexAttribPointer(attribute, 3, GL_FLOAT, GL_FALSE, 9 * sizeof(GLfloat),
                              reinterpret_cast<const void*>(attribute * 3 * sizeof(GLfloat)));
    }
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_CULL_FACE);

    glm::mat4 rotation(1.0f);
    glm::vec2 spin(0.4f, 0.7f);  // radians per second around the X and Y axes
    bool dragging = false;
    Uint64 last = SDL_GetTicks();
    bool running = true;
    while (running) {
        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            if (event.type == SDL_EVENT_QUIT) running = false;
            if (event.type == SDL_EVENT_FINGER_DOWN) dragging = true;
            if (event.type == SDL_EVENT_FINGER_UP) dragging = false;
            if (event.type == SDL_EVENT_FINGER_MOTION && dragging) {
                // dx/dy are fractions of the screen; turn them into a spin speed.
                spin = glm::vec2(event.tfinger.dy, event.tfinger.dx) * 120.0f;
            }
        }
        const Uint64 now = SDL_GetTicks();
        const float dt = (now - last) / 1000.0f;
        last = now;
        rotation = glm::rotate(glm::mat4(1.0f), spin.x * dt, glm::vec3(1, 0, 0)) *
                   glm::rotate(glm::mat4(1.0f), spin.y * dt, glm::vec3(0, 1, 0)) * rotation;
        if (!dragging) spin *= 0.995f + 0.005f * (glm::length(spin) < 0.5f);  // slow down, but keep moving

        int width = 0, height = 0;
        SDL_GetWindowSizeInPixels(window, &width, &height);
        glViewport(0, 0, width, height);
        glClearColor(0.07f, 0.08f, 0.11f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        const float aspect = height > 0 ? static_cast<float>(width) / height : 1.0f;
        const glm::mat4 projection = glm::perspective(glm::radians(45.0f), aspect, 0.1f, 100.0f);
        const glm::mat4 view = glm::lookAt(glm::vec3(0, 0, aspect < 1 ? 4.0f : 3.0f), glm::vec3(0), glm::vec3(0, 1, 0));
        const glm::mat4 viewProjection = projection * view;
        glUseProgram(program);
        glUniformMatrix4fv(modelLocation, 1, GL_FALSE, glm::value_ptr(rotation));
        glUniformMatrix4fv(viewProjectionLocation, 1, GL_FALSE, glm::value_ptr(viewProjection));
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_SHORT, nullptr);
        SDL_GL_SwapWindow(window);
    }

    SDL_GL_DestroyContext(context);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 0;
}
