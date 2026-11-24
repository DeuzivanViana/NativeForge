// A finger painting app: raylib draws, raygui provides the colour swatches, the brush size
// slider and the Undo and Clear buttons.
// Every stroke is kept as a list of points, so Undo simply paints the remaining strokes again,
// and the drawing survives turning the phone.
#include "raylib.h"
#define RAYGUI_IMPLEMENTATION  // in exactly one source file
#include "raygui.h"

#include <algorithm>
#include <array>
#include <vector>

struct Stroke {
    Color color;
    float size;  // brush diameter, in units (see Toolbar)
    std::vector<Vector2> points;  // in units too, so strokes keep their place when the screen turns
};

constexpr std::array<Color, 8> PALETTE{{
    {30, 30, 36, 255}, {230, 70, 60, 255}, {245, 160, 40, 255}, {250, 215, 60, 255},
    {80, 190, 90, 255}, {60, 140, 230, 255}, {150, 90, 210, 255}, {255, 255, 255, 255},
}};

// Where the controls go, at the bottom where thumbs reach. Sizes are in units of 1/360 of the
// screen's short side, so the toolbar fits small and large phones, upright or sideways.
struct Toolbar {
    float unit;
    std::array<Rectangle, PALETTE.size()> swatches;
    Rectangle undo, clear, slider;
    int textSize;
    float top;  // the canvas ends here

    Toolbar(float width, float height) {
        unit = std::min(width, height) / 360.0f;
        const float margin = 8 * unit;
        const float swatch = std::min((width - (PALETTE.size() + 1) * margin) / PALETTE.size(), 40 * unit);
        const float buttonHeight = 40 * unit, buttonWidth = 80 * unit;
        top = height - (margin + swatch + margin + buttonHeight + margin);
        // Swatches centred on the first row.
        const float rowWidth = PALETTE.size() * swatch + (PALETTE.size() - 1) * margin;
        for (std::size_t i = 0; i < swatches.size(); i++) {
            swatches[i] = {(width - rowWidth) / 2 + i * (swatch + margin), top + margin, swatch, swatch};
        }
        // Second row: Undo, Clear, then the slider with its name on the left and value on the right.
        const float row = top + margin + swatch + margin;
        undo = {margin, row, buttonWidth, buttonHeight};
        clear = {2 * margin + buttonWidth, row, buttonWidth, buttonHeight};
        const float nameRoom = 40 * unit, valueRoom = 36 * unit;
        const float sliderLeft = clear.x + buttonWidth + margin + nameRoom;
        const float sliderWidth = std::max(40 * unit, std::min(width - sliderLeft - valueRoom - margin, 260 * unit));
        slider = {sliderLeft, row + 8 * unit, sliderWidth, buttonHeight - 16 * unit};
        textSize = static_cast<int>(16 * unit);
    }
};

static void paintSegment(Vector2 from, Vector2 to, float size, Color color, float unit) {
    const Vector2 a{from.x * unit, from.y * unit}, b{to.x * unit, to.y * unit};
    DrawLineEx(a, b, size * unit, color);
    DrawCircleV(b, size * unit / 2, color);  // round joints between segments
}

static void paintStroke(const Stroke& stroke, float unit) {
    if (stroke.points.empty()) return;
    DrawCircleV({stroke.points[0].x * unit, stroke.points[0].y * unit}, stroke.size * unit / 2, stroke.color);
    for (std::size_t i = 1; i < stroke.points.size(); i++) {
        paintSegment(stroke.points[i - 1], stroke.points[i], stroke.size, stroke.color, unit);
    }
}

// Paints every stroke again on a canvas the size of the screen.
static void repaint(RenderTexture2D& canvas, const std::vector<Stroke>& strokes, float unit) {
    BeginTextureMode(canvas);
    ClearBackground(RAYWHITE);
    for (const Stroke& stroke : strokes) paintStroke(stroke, unit);
    EndTextureMode();
}

int main() {
    InitWindow(0, 0, "@PROJECT_NAME@");  // 0 x 0: the whole screen
    SetTargetFPS(60);

    int width = GetScreenWidth(), height = GetScreenHeight();
    Toolbar bar(width, height);
    RenderTexture2D canvas = LoadRenderTexture(width, height);
    std::vector<Stroke> strokes;
    Color color = PALETTE[5];
    float brushSize = 8.0f;
    bool drawing = false;  // the finger went down on the canvas and has not lifted yet
    repaint(canvas, strokes, bar.unit);

    while (!WindowShouldClose()) {
        // The phone turned: new layout, new canvas, same drawing.
        if (GetScreenWidth() != width || GetScreenHeight() != height) {
            width = GetScreenWidth();
            height = GetScreenHeight();
            bar = Toolbar(width, height);
            UnloadRenderTexture(canvas);
            canvas = LoadRenderTexture(width, height);
            repaint(canvas, strokes, bar.unit);
            drawing = false;
        }

        // A touch arrives as the left mouse button. Strokes only start on the canvas, so a
        // finger that slides off a button never paints.
        const Vector2 mouse = GetMousePosition();
        const Vector2 point{mouse.x / bar.unit, mouse.y / bar.unit};
        if (IsMouseButtonPressed(MOUSE_BUTTON_LEFT) && mouse.y < bar.top) {
            drawing = true;
            strokes.push_back({color, brushSize, {point}});
            BeginTextureMode(canvas);
            paintStroke(strokes.back(), bar.unit);
            EndTextureMode();
        } else if (drawing && IsMouseButtonDown(MOUSE_BUTTON_LEFT)) {
            std::vector<Vector2>& points = strokes.back().points;
            const Vector2 last = points.back();
            const float dx = point.x - last.x, dy = point.y - last.y;
            if (dx * dx + dy * dy >= 1.0f) {  // skip points less than a unit apart
                BeginTextureMode(canvas);
                paintSegment(last, point, brushSize, color, bar.unit);
                EndTextureMode();
                points.push_back(point);
            }
        } else {
            drawing = false;
        }

        BeginDrawing();
        ClearBackground(RAYWHITE);
        // Render textures are stored upside down: a negative height flips the image back.
        DrawTextureRec(canvas.texture, {0, 0, static_cast<float>(width), -static_cast<float>(height)}, {0, 0}, WHITE);

        DrawRectangleRec({0, bar.top, static_cast<float>(width), height - bar.top}, {235, 236, 240, 255});
        DrawLineEx({0, bar.top}, {static_cast<float>(width), bar.top}, bar.unit, {200, 200, 210, 255});
        GuiSetStyle(DEFAULT, TEXT_SIZE, bar.textSize);
        for (std::size_t i = 0; i < PALETTE.size(); i++) {
            const Rectangle& r = bar.swatches[i];
            const bool selected = ColorToInt(PALETTE[i]) == ColorToInt(color);
            DrawRectangleRounded(r, 0.3f, 6, PALETTE[i]);
            DrawRectangleRoundedLinesEx(r, 0.3f, 6, (selected ? 3 : 1) * bar.unit, selected ? BLACK : GRAY);
            if (!drawing && IsMouseButtonPressed(MOUSE_BUTTON_LEFT) && CheckCollisionPointRec(mouse, r)) color = PALETTE[i];
        }
        if (GuiButton(bar.undo, "Undo") && !strokes.empty()) {
            strokes.pop_back();
            repaint(canvas, strokes, bar.unit);
        }
        if (GuiButton(bar.clear, "Clear")) {
            strokes.clear();
            repaint(canvas, strokes, bar.unit);
        }
        GuiSlider(bar.slider, "Size", TextFormat("%d", static_cast<int>(brushSize)), &brushSize, 2.0f, 40.0f);
        EndDrawing();
    }

    UnloadRenderTexture(canvas);
    CloseWindow();
    return 0;
}
