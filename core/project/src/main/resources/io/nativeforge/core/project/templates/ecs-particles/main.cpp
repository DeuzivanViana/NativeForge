#include "raylib.h"

#include <entt/entt.hpp>

#include <algorithm>
#include <cmath>
#include <cstdio>

// Thousands of particles with EnTT, an entity-component-system library, drawn by raylib.
// Entities are just ids; components are plain structs attached to them; systems are loops
// over every entity that has a given set of components. Touch and drag to spray particles.

struct Position { Vector2 value; };
struct Velocity { Vector2 value; };
struct Lifetime { float remaining; float total; };
struct Tint { Color color; };
struct Bouncy {};  // a "tag" component: no data, it only marks entities

namespace {

// Distances are in units of 1/360 of the screen's short side, so the spray looks the same
// on small and large phones, upright or sideways.
float unit() { return std::min(GetScreenWidth(), GetScreenHeight()) / 360.0f; }

// Largest font size, up to size, at which text fits in maxWidth pixels.
int fitText(const char* text, int size, float maxWidth) {
    while (size > 10 && MeasureText(text, size) > maxWidth) --size;
    return size;
}

void spawn(entt::registry& registry, Vector2 at, int count) {
    const float u = unit();
    for (int i = 0; i < count; ++i) {
        const float angle = GetRandomValue(0, 359) * DEG2RAD;
        const float speed = GetRandomValue(50, 216) * u;
        const auto entity = registry.create();
        registry.emplace<Position>(entity, at);
        registry.emplace<Velocity>(entity, Vector2{std::cos(angle) * speed, std::sin(angle) * speed - 100 * u});
        const float life = GetRandomValue(150, 400) / 100.0f;
        registry.emplace<Lifetime>(entity, life, life);
        registry.emplace<Tint>(entity, ColorFromHSV(static_cast<float>(GetRandomValue(0, 359)), 0.75f, 1.0f));
        if (GetRandomValue(0, 2) == 0) registry.emplace<Bouncy>(entity);
    }
}

// System: integrates velocity and gravity for everything that moves.
void movementSystem(entt::registry& registry, float dt) {
    const float gravity = 300 * unit();
    registry.view<Position, Velocity>().each([dt, gravity](Position& p, Velocity& v) {
        v.value.y += gravity * dt;
        p.value.x += v.value.x * dt;
        p.value.y += v.value.y * dt;
    });
}

// System: only entities tagged Bouncy bounce off the bottom and the sides.
void bounceSystem(entt::registry& registry) {
    const float width = static_cast<float>(GetScreenWidth());
    const float floor = static_cast<float>(GetScreenHeight());
    registry.view<Position, Velocity, Bouncy>().each([&](Position& p, Velocity& v) {
        if (p.value.y > floor) { p.value.y = floor; v.value.y *= -0.6f; }
        if (p.value.x < 0 || p.value.x > width) v.value.x *= -1.0f;
    });
}

// System: ages particles and destroys the expired ones (EnTT allows destroying the current entity of a view).
void lifetimeSystem(entt::registry& registry, float dt) {
    auto view = registry.view<Lifetime>();
    for (auto entity : view) {
        auto& life = view.get<Lifetime>(entity);
        life.remaining -= dt;
        if (life.remaining <= 0) registry.destroy(entity);
    }
}

void renderSystem(entt::registry& registry, float radius) {
    registry.view<const Position, const Lifetime, const Tint>().each(
        [radius](const Position& p, const Lifetime& life, const Tint& tint) {
            const float t = life.remaining / life.total;
            DrawCircleV(p.value, radius * (0.4f + 0.6f * t), Fade(tint.color, t));
        });
}

} // namespace

int main() {
    InitWindow(0, 0, "@PROJECT_NAME@");
    SetTargetFPS(60);
    entt::registry registry;
    spawn(registry, Vector2{GetScreenWidth() / 2.0f, GetScreenHeight() / 3.0f}, 400);

    while (!WindowShouldClose()) {
        const float dt = GetFrameTime();
        if (IsMouseButtonDown(MOUSE_BUTTON_LEFT)) spawn(registry, GetMousePosition(), 12);

        movementSystem(registry, dt);
        bounceSystem(registry);
        lifetimeSystem(registry, dt);

        BeginDrawing();
        ClearBackground(Color{14, 15, 20, 255});
        const float u = unit(), margin = 12 * u, room = GetScreenWidth() - 2 * margin;
        renderSystem(registry, 3 * u);
        char status[96];
        std::snprintf(status, sizeof status, "%d particles  |  %d FPS",
                      static_cast<int>(registry.view<Position>().size()), GetFPS());
        const char* hint = "Touch and drag to spray particles";
        const int statusSize = fitText(status, static_cast<int>(18 * u), room);
        DrawText(status, static_cast<int>(margin), static_cast<int>(margin), statusSize, RAYWHITE);
        DrawText(hint, static_cast<int>(margin), static_cast<int>(margin + statusSize * 1.4f),
                 fitText(hint, static_cast<int>(14 * u), room), GRAY);
        EndDrawing();
    }
    CloseWindow();
    return 0;
}
