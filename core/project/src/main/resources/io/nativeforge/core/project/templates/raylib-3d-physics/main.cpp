// raylib draws in 3D, Box3D simulates and raygui provides the buttons.
// Drag with one finger to orbit, pinch to zoom, tap the floor to drop a box or a ball.
#include "raylib.h"
#include "raymath.h"
#include "rlgl.h"
#define RAYGUI_IMPLEMENTATION  // in exactly one source file
#include "raygui.h"
#include <box3d/box3d.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

// Where the controls go. Sizes are in units of 1/360 of the screen's short side, so the
// toolbar fits small and large phones, upright or sideways.
struct Toolbar {
    float unit;
    std::array<Rectangle, 4> buttons;  // Reset, Pause, Box/Ball, Shoot
    Rectangle slider;
    Vector2 stats;
    int textSize;
    float bottom;  // touches below this move the camera or drop things

    explicit Toolbar(float width, float height) {
        unit = std::min(width, height) / 360.0f;
        const float margin = 8 * unit, buttonHeight = 44 * unit;
        const float buttonWidth = std::min((width - 5 * margin) / 4, 110 * unit);
        for (int i = 0; i < 4; ++i) buttons[i] = {margin + i * (buttonWidth + margin), margin, buttonWidth, buttonHeight};
        // raygui draws the slider's name on its left and its value on its right.
        const float nameRoom = 44 * unit, valueRoom = 60 * unit;
        const float sliderWidth = std::min(width - 2 * margin - nameRoom - valueRoom, 300 * unit);
        slider = {margin + nameRoom, margin + buttonHeight + 12 * unit, sliderWidth, 28 * unit};
        textSize = static_cast<int>(16 * unit);
        stats = {margin, slider.y + slider.height + 10 * unit};
        bottom = stats.y + textSize + margin;
    }
};

enum class Kind { Box, Ball };

struct Thing {
    b3BodyId body;
    Kind kind;
    float size;  // half extent or radius, metres
    Color color;
};

static const std::array<Color, 5> kPalette{{
    {229, 192, 123, 255}, {97, 175, 239, 255}, {152, 195, 121, 255}, {224, 108, 117, 255}, {198, 120, 221, 255}}};

static b3BodyId addBody(b3WorldId world, Kind kind, b3Vec3 position, float size, b3Vec3 velocity) {
    b3BodyDef def = b3DefaultBodyDef();
    def.type = b3_dynamicBody;
    def.position = position;
    def.linearVelocity = velocity;
    b3BodyId body = b3CreateBody(world, &def);

    b3ShapeDef shape = b3DefaultShapeDef();
    shape.density = 1.0f;
    shape.baseMaterial.friction = 0.6f;
    shape.baseMaterial.restitution = kind == Kind::Ball ? 0.5f : 0.1f;
    if (kind == Kind::Box) {
        b3BoxHull hull = b3MakeCubeHull(size);
        b3CreateHullShape(body, &shape, &hull.base);
    } else {
        b3Sphere sphere{{0.0f, 0.0f, 0.0f}, size};
        b3CreateSphereShape(body, &shape, &sphere);
    }
    return body;
}

// A small pyramid of boxes so there is something to knock over.
static void buildPyramid(b3WorldId world, std::vector<Thing>& things) {
    const float h = 0.5f;
    for (int layer = 0; layer < 4; ++layer) {
        const int count = 4 - layer;
        for (int x = 0; x < count; ++x) {
            for (int z = 0; z < count; ++z) {
                const b3Vec3 p{(x - (count - 1) * 0.5f) * 2.05f * h, h + layer * 2.0f * h,
                               (z - (count - 1) * 0.5f) * 2.05f * h};
                things.push_back({addBody(world, Kind::Box, p, h, {0, 0, 0}), Kind::Box, h,
                                  kPalette[(layer + x + z) % kPalette.size()]});
            }
        }
    }
}

static void drawThing(const Thing& thing) {
    const b3Vec3 p = b3Body_GetPosition(thing.body);
    float angle = 0.0f;
    const b3Vec3 axis = b3GetAxisAngle(&angle, b3Body_GetRotation(thing.body));
    rlPushMatrix();
    rlTranslatef(p.x, p.y, p.z);
    rlRotatef(angle * RAD2DEG, axis.x, axis.y, axis.z);
    const float d = thing.size * 2.0f;
    if (thing.kind == Kind::Box) {
        DrawCube(Vector3Zero(), d, d, d, thing.color);
        DrawCubeWires(Vector3Zero(), d, d, d, Fade(BLACK, 0.45f));
    } else {
        DrawSphereEx(Vector3Zero(), thing.size, 10, 14, thing.color);
        DrawSphereWires(Vector3Zero(), thing.size * 1.01f, 6, 8, Fade(BLACK, 0.35f));
    }
    rlPopMatrix();
}

int main() {
    InitWindow(0, 0, "@PROJECT_NAME@");
    SetTargetFPS(60);

    b3WorldDef worldDef = b3DefaultWorldDef();
    worldDef.gravity = {0.0f, -10.0f, 0.0f};
    b3WorldId world = b3CreateWorld(&worldDef);

    // Ground: a static slab whose top face is y = 0.
    b3BodyDef groundDef = b3DefaultBodyDef();
    groundDef.position = {0.0f, -0.5f, 0.0f};
    b3BodyId ground = b3CreateBody(world, &groundDef);
    b3BoxHull groundHull = b3MakeBoxHull(20.0f, 0.5f, 20.0f);
    b3ShapeDef groundShape = b3DefaultShapeDef();
    b3CreateHullShape(ground, &groundShape, &groundHull.base);

    std::vector<Thing> things;
    buildPyramid(world, things);

    Camera3D camera{};
    camera.up = {0.0f, 1.0f, 0.0f};
    camera.fovy = 50.0f;
    camera.projection = CAMERA_PERSPECTIVE;
    float yaw = 0.8f, pitch = 0.45f, distance = 14.0f;

    Kind nextKind = Kind::Box;
    float nextSize = 0.5f;
    bool paused = false;
    Vector2 pressAt{}, lastTouch{};
    float lastPinch = 0.0f;
    bool dragging = false;

    while (!WindowShouldClose()) {
        const Toolbar bar(static_cast<float>(GetScreenWidth()), static_cast<float>(GetScreenHeight()));
        GuiSetStyle(DEFAULT, TEXT_SIZE, bar.textSize);
        const float tapSlop = 8 * bar.unit;  // how far a finger may move and still count as a tap
        const float orbitSpeed = 0.002f / bar.unit;  // radians per pixel, the same feel on every screen

        // Camera: one finger orbits, two fingers zoom.
        const Vector2 touch = GetMousePosition();
        if (GetTouchPointCount() >= 2) {
            const float pinch = Vector2Distance(GetTouchPosition(0), GetTouchPosition(1));
            if (lastPinch > 0.0f) distance = Clamp(distance * lastPinch / pinch, 5.0f, 40.0f);
            lastPinch = pinch;
            dragging = true;  // a pinch never drops anything
        } else {
            lastPinch = 0.0f;
            if (IsMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                pressAt = lastTouch = touch;
                dragging = touch.y < bar.bottom;  // presses on the toolbar are for raygui
            } else if (IsMouseButtonDown(MOUSE_BUTTON_LEFT) && touch.y >= bar.bottom) {
                if (Vector2Distance(touch, pressAt) > tapSlop) dragging = true;
                if (dragging) {
                    yaw -= (touch.x - lastTouch.x) * orbitSpeed;
                    pitch = Clamp(pitch + (touch.y - lastTouch.y) * orbitSpeed * 0.7f, 0.08f, 1.45f);
                }
                lastTouch = touch;
            }
        }
        camera.target = {0.0f, 1.5f, 0.0f};
        camera.position = {distance * std::cos(pitch) * std::sin(yaw), 1.5f + distance * std::sin(pitch),
                           distance * std::cos(pitch) * std::cos(yaw)};

        // Tap: drop the selected shape above the point of the floor under the finger.
        if (IsMouseButtonReleased(MOUSE_BUTTON_LEFT) && !dragging && touch.y >= bar.bottom) {
            const Ray ray = GetScreenToWorldRay(touch, camera);
            if (ray.direction.y < -0.01f) {
                const float t = -ray.position.y / ray.direction.y;
                const Vector3 hit = Vector3Add(ray.position, Vector3Scale(ray.direction, t));
                const b3Vec3 p{Clamp(hit.x, -18.0f, 18.0f), 8.0f, Clamp(hit.z, -18.0f, 18.0f)};
                things.push_back({addBody(world, nextKind, p, nextSize, {0, 0, 0}), nextKind, nextSize,
                                  kPalette[things.size() % kPalette.size()]});
            }
        }

        if (!paused) b3World_Step(world, 1.0f / 60.0f, 4);

        BeginDrawing();
        ClearBackground(Color{24, 26, 33, 255});
        BeginMode3D(camera);
        DrawPlane({0.0f, 0.0f, 0.0f}, {40.0f, 40.0f}, Color{44, 48, 58, 255});
        DrawGrid(40, 1.0f);
        for (const Thing& thing : things) drawThing(thing);
        EndMode3D();

        if (GuiButton(bar.buttons[0], "Reset")) {
            for (const Thing& thing : things) b3DestroyBody(thing.body);
            things.clear();
            buildPyramid(world, things);
        }
        if (GuiButton(bar.buttons[1], paused ? "Play" : "Pause")) paused = !paused;
        if (GuiButton(bar.buttons[2], nextKind == Kind::Box ? "Box" : "Ball")) {
            nextKind = nextKind == Kind::Box ? Kind::Ball : Kind::Box;
        }
        if (GuiButton(bar.buttons[3], "Shoot")) {
            // Fire a ball from the camera towards the target.
            const Vector3 dir = Vector3Normalize(Vector3Subtract(camera.target, camera.position));
            const b3Vec3 p{camera.position.x, camera.position.y, camera.position.z};
            const b3Vec3 v{dir.x * 30.0f, dir.y * 30.0f, dir.z * 30.0f};
            things.push_back({addBody(world, Kind::Ball, p, 0.4f, v), Kind::Ball, 0.4f, RAYWHITE});
        }
        GuiSlider(bar.slider, "Size", TextFormat("%.1f m", nextSize * 2), &nextSize, 0.2f, 1.5f);
        DrawText(TextFormat("Bodies: %d  Awake: %d  FPS: %d", static_cast<int>(things.size()),
                            b3World_GetAwakeBodyCount(world), GetFPS()),
                 static_cast<int>(bar.stats.x), static_cast<int>(bar.stats.y), bar.textSize, RAYWHITE);
        EndDrawing();
    }

    b3DestroyWorld(world);
    CloseWindow();
    return 0;
}
