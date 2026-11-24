// raylib draws, Box2D simulates and raygui provides the buttons.
// Tap anywhere below the buttons to drop a crate.
#include "raylib.h"
#define RAYGUI_IMPLEMENTATION  // in exactly one source file
#include "raygui.h"
#include <box2d/box2d.h>

#include <algorithm>
#include <array>
#include <vector>

struct Crate {
    b2Body* body;
    float size;  // metres
    Color color;
};

// Where the controls go. Sizes are in units of 1/360 of the screen's short side, so the
// toolbar fits small and large phones, upright or sideways.
struct Toolbar {
    float unit;
    Rectangle reset, pause, slider;
    Vector2 stats;
    int textSize;
    float bottom;  // taps below this drop crates

    explicit Toolbar(float width, float height) {
        unit = std::min(width, height) / 360.0f;
        const float margin = 8 * unit, buttonHeight = 44 * unit;
        const float buttonWidth = std::min((width - 3 * margin) / 2, 140 * unit);
        reset = {margin, margin, buttonWidth, buttonHeight};
        pause = {2 * margin + buttonWidth, margin, buttonWidth, buttonHeight};
        // raygui draws the slider's name on its left and its value on its right.
        const float nameRoom = 44 * unit, valueRoom = 60 * unit;
        const float sliderWidth = std::min(width - 2 * margin - nameRoom - valueRoom, 300 * unit);
        slider = {margin + nameRoom, reset.y + buttonHeight + 12 * unit, sliderWidth, 28 * unit};
        textSize = static_cast<int>(16 * unit);
        stats = {margin, slider.y + slider.height + 10 * unit};
        bottom = stats.y + textSize + margin;
    }
};

static b2Body* addCrate(b2World& world, Vector2 pixels, float size, float pixelsPerMetre) {
    b2BodyDef def;
    def.type = b2_dynamicBody;
    def.position.Set(pixels.x / pixelsPerMetre, pixels.y / pixelsPerMetre);
    b2Body* body = world.CreateBody(&def);

    b2PolygonShape shape;
    shape.SetAsBox(size / 2, size / 2);
    b2FixtureDef fixture;
    fixture.shape = &shape;
    fixture.density = 1.0f;
    fixture.friction = 0.5f;
    fixture.restitution = 0.2f;
    body->CreateFixture(&fixture);
    return body;
}

int main() {
    InitWindow(0, 0, "@PROJECT_NAME@");  // 0 x 0: the whole screen
    SetTargetFPS(60);

    // Box2D works in metres. A metre is 20 units on screen, the same share of every phone.
    const float pixelsPerMetre = 20 * Toolbar(GetScreenWidth(), GetScreenHeight()).unit;

    // Screen coordinates grow downwards, so gravity is +y.
    b2World world(b2Vec2(0.0f, 10.0f));
    b2BodyDef groundDef;
    b2Body* ground = world.CreateBody(&groundDef);
    b2PolygonShape groundShape;
    groundShape.SetAsBox(1000.0f, 0.5f);  // wide enough for any screen, upright or sideways
    ground->CreateFixture(&groundShape, 0.0f);

    const std::array<Color, 4> palette{{{229, 192, 123, 255}, {97, 175, 239, 255}, {152, 195, 121, 255}, {224, 108, 117, 255}}};
    std::vector<Crate> crates;
    float crateSize = 1.0f;
    bool paused = false;

    while (!WindowShouldClose()) {
        const auto width = static_cast<float>(GetScreenWidth());
        const auto height = static_cast<float>(GetScreenHeight());
        const Toolbar bar(width, height);
        GuiSetStyle(DEFAULT, TEXT_SIZE, bar.textSize);

        // The floor's top sits 2 units above the bottom edge, also after the screen rotates.
        const float floorTop = height - 40 * bar.unit;
        ground->SetTransform(b2Vec2(0.0f, floorTop / pixelsPerMetre + 0.5f), 0.0f);

        const Vector2 touch = GetMousePosition();
        if (IsMouseButtonPressed(MOUSE_BUTTON_LEFT) && touch.y > bar.bottom && touch.y < floorTop) {
            crates.push_back({addCrate(world, touch, crateSize, pixelsPerMetre), crateSize,
                              palette[crates.size() % palette.size()]});
        }
        if (!paused) world.Step(1.0f / 60.0f, 8, 3);

        BeginDrawing();
        ClearBackground(Color{24, 26, 33, 255});
        DrawRectangle(0, static_cast<int>(floorTop), static_cast<int>(width), static_cast<int>(height - floorTop),
                      Color{60, 64, 72, 255});
        for (const Crate& crate : crates) {
            const b2Vec2 p = crate.body->GetPosition();
            const float side = crate.size * pixelsPerMetre;
            DrawRectanglePro({p.x * pixelsPerMetre, p.y * pixelsPerMetre, side, side}, {side / 2, side / 2},
                             crate.body->GetAngle() * RAD2DEG, crate.color);
        }

        if (GuiButton(bar.reset, "Reset")) {
            for (const Crate& crate : crates) world.DestroyBody(crate.body);
            crates.clear();
        }
        if (GuiButton(bar.pause, paused ? "Play" : "Pause")) paused = !paused;
        GuiSlider(bar.slider, "Size", TextFormat("%.1f m", crateSize), &crateSize, 0.3f, 2.5f);
        DrawText(TextFormat("Crates: %d   FPS: %d", static_cast<int>(crates.size()), GetFPS()),
                 static_cast<int>(bar.stats.x), static_cast<int>(bar.stats.y), bar.textSize, RAYWHITE);
        EndDrawing();
    }

    CloseWindow();
    return 0;
}
