#include "raylib.h"

#include <math.h>

/* Largest font size, up to size, at which text fits in max_width pixels. */
static int fit_text(const char *text, int size, float max_width)
{
    while (size > 10 && MeasureText(text, size) > max_width) size--;
    return size;
}

int main(void)
{
    InitWindow(0, 0, "@PROJECT_NAME@"); /* 0 x 0: the whole screen */
    SetTargetFPS(60);

    Color colors[] = {{229, 192, 123, 255}, {97, 175, 239, 255}, {152, 195, 121, 255}, {224, 108, 117, 255}};
    int color = 0;
    Vector2 ball = {GetScreenWidth() / 3.0f, GetScreenHeight() / 3.0f};
    Vector2 direction = {0.8f, 0.6f};

    while (!WindowShouldClose()) {
        const float width = (float)GetScreenWidth(), height = (float)GetScreenHeight();
        /* Sizes are in units of 1/360 of the screen's short side, so the game looks the same
           on small and large phones, upright or sideways. */
        const float unit = fminf(width, height) / 360.0f;
        const float radius = 20 * unit, speed = 180 * unit * GetFrameTime();

        if (IsMouseButtonPressed(MOUSE_BUTTON_LEFT)) color = (color + 1) % 4; /* a tap counts as a click */
        ball.x += direction.x * speed;
        ball.y += direction.y * speed;
        if (ball.x < radius) direction.x = fabsf(direction.x);
        if (ball.x > width - radius) direction.x = -fabsf(direction.x);
        if (ball.y < radius) direction.y = fabsf(direction.y);
        if (ball.y > height - radius) direction.y = -fabsf(direction.y);

        const float margin = 16 * unit;
        const char *title = "Hello from @PROJECT_NAME@!";
        const char *hint = "Tap to change the colour";
        const int titleSize = fit_text(title, (int)(28 * unit), width - 2 * margin);
        const int hintSize = fit_text(hint, (int)(18 * unit), width - 2 * margin);

        BeginDrawing();
        ClearBackground((Color){24, 26, 33, 255});
        DrawText(title, (int)margin, (int)margin, titleSize, RAYWHITE);
        DrawText(hint, (int)margin, (int)(margin + titleSize * 1.3f), hintSize, GRAY);
        DrawCircleV(ball, radius, colors[color]);
        EndDrawing();
    }

    CloseWindow();
    return 0;
}
