#include "raylib.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <string>

// Largest font size, up to size, at which text fits in maxWidth pixels.
static int fitText(const std::string& text, int size, float maxWidth) {
    while (size > 10 && MeasureText(text.c_str(), size) > maxWidth) --size;
    return size;
}

struct Ball {
    Vector2 position{};
    Vector2 direction{0.8f, 0.6f};

    // radius and speed are in pixels, scaled to the screen by the caller.
    void update(float width, float height, float radius, float speed) {
        position.x += direction.x * speed;
        position.y += direction.y * speed;
        if (position.x < radius) direction.x = std::fabs(direction.x);
        if (position.x > width - radius) direction.x = -std::fabs(direction.x);
        if (position.y < radius) direction.y = std::fabs(direction.y);
        if (position.y > height - radius) direction.y = -std::fabs(direction.y);
    }
};

int main() {
    InitWindow(0, 0, "@PROJECT_NAME@");  // 0 x 0: the whole screen
    SetTargetFPS(60);

    const std::array<Color, 4> colors{{{229, 192, 123, 255}, {97, 175, 239, 255}, {152, 195, 121, 255}, {224, 108, 117, 255}}};
    const std::string title = "Hello from @PROJECT_NAME@!";
    const std::string hint = "Tap to change the colour";
    std::size_t color = 0;
    Ball ball{{GetScreenWidth() / 3.0f, GetScreenHeight() / 3.0f}};

    while (!WindowShouldClose()) {
        const auto width = static_cast<float>(GetScreenWidth());
        const auto height = static_cast<float>(GetScreenHeight());
        // Sizes are in units of 1/360 of the screen's short side, so the game looks the same
        // on small and large phones, upright or sideways.
        const float unit = std::min(width, height) / 360.0f;
        const float radius = 20 * unit;

        if (IsMouseButtonPressed(MOUSE_BUTTON_LEFT)) color = (color + 1) % colors.size();  // a tap counts as a click
        ball.update(width, height, radius, 180 * unit * GetFrameTime());

        const float margin = 16 * unit;
        const int titleSize = fitText(title, static_cast<int>(28 * unit), width - 2 * margin);
        const int hintSize = fitText(hint, static_cast<int>(18 * unit), width - 2 * margin);

        BeginDrawing();
        ClearBackground(Color{24, 26, 33, 255});
        DrawText(title.c_str(), static_cast<int>(margin), static_cast<int>(margin), titleSize, RAYWHITE);
        DrawText(hint.c_str(), static_cast<int>(margin), static_cast<int>(margin + titleSize * 1.3f), hintSize, GRAY);
        DrawCircleV(ball.position, radius, colors[color]);
        EndDrawing();
    }

    CloseWindow();
    return 0;
}
