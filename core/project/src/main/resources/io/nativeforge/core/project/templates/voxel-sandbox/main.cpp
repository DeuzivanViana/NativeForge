// Voxel sandbox: an endless block world made from noise. Walk, fly, break and place blocks, and
// throw physical things from the hotbar. raylib draws, Box3D simulates the things and the terrain
// near the player. The parts live in their own files; game.hpp says which.
//
// Touch: the pad on the left walks (push it past the edge to run), drag on the world to look, tap
// to place the block or throw the thing in your hand, hold a finger still on a block to break it.
// Jump: tap, double tap to fly. The grid button at the end of the hotbar opens the inventory with
// every block and thing; pick the first entry (the hand) to punch things around.
// Keyboard and mouse: WASD, Space, Shift, Ctrl, left click breaks, right click places, E inventory.
#include "game.hpp"

#include <box3d/box3d.h>

#include <algorithm>
#include <cmath>

namespace {
constexpr float kReach = 5.5f;  // blocks
const Color kSky{148, 200, 238, 255};

int cell(float v) { return static_cast<int>(std::floor(v)); }

// A dry spot near the origin, away from water, to start on.
Vector3 findSpawn(World& world) {
    int sx = 0, sz = 0;
    for (int ring = 0; ring < 200; ring += 4) {
        bool found = false;
        for (int dx = -ring; dx <= ring && !found; dx += 4) {
            for (int dz = -ring; dz <= ring && !found; dz += 4) {
                const int h = world.surfaceHeight(dx, dz);
                if (h > kSeaLevel + 1 && h < 34) {
                    sx = dx;
                    sz = dz;
                    found = true;
                }
            }
        }
        if (found) break;
    }
    world.prepareAround({sx + 0.5f, 0.0f, sz + 0.5f});
    // Stand on the highest block of the column (a tree top is fine too: it is a lookout).
    int y = kHeight - 1;
    while (y > 0 && !world.solid(sx, y, sz)) --y;
    return {sx + 0.5f, y + 1.0f, sz + 0.5f};
}

bool anyThing(b3ShapeId, void* context) {
    *static_cast<bool*>(context) = true;
    return false;  // one is enough
}

// True if a thing (not debris) is in the block: a block placed there would trap it.
bool thingInBlock(b3WorldId physics, int x, int y, int z) {
    bool found = false;
    b3QueryFilter filter = b3DefaultQueryFilter();
    filter.categoryBits = kPlayerBit;
    filter.maskBits = kThingBit;
    const b3AABB box{{x + 0.05f, y + 0.05f, z + 0.05f}, {x + 0.95f, y + 0.95f, z + 0.95f}};
    b3World_OverlapAABB(physics, box, filter, anyThing, &found);
    return found;
}
}  // namespace

int main() {
    InitWindow(0, 0, "@PROJECT_NAME@");
    SetTargetFPS(60);

    b3WorldDef worldDef = b3DefaultWorldDef();
    worldDef.gravity = {0.0f, -10.0f, 0.0f};
    b3WorldId physics = b3CreateWorld(&worldDef);
    {
        World world(physics);
        Things things(physics);
        Ui ui;
        Player player;
        player.create(physics, findSpawn(world));

        Camera3D camera{};
        camera.up = {0.0f, 1.0f, 0.0f};
        camera.fovy = 70.0f;
        camera.projection = CAMERA_PERSPECTIVE;
        float swing = 1.0f;  // 0 to 1: the hand's swing after using or breaking
        double time = 0.0;

        while (!WindowShouldClose()) {
            const float dt = std::min(GetFrameTime(), 1.0f / 20.0f);
            time += dt;

            // --- Input and the player ------------------------------------------------------------
            const Actions act = ui.update(player, dt, time);
            const bool wasFlying = player.flying;
            player.update(world, physics, act.input, dt, time);
            if (player.flying != wasFlying) ui.say(player.flying ? "Flying" : "Walking", time);
            camera.position = player.eye();
            camera.target = Vector3Add(camera.position, player.look());

            // --- Breaking, placing, throwing -----------------------------------------------------
            if (act.breakBlock) {
                const Ray ray = GetScreenToWorldRay(act.breakPoint, camera);
                RayHit hit;
                if (world.raycast(ray.position, ray.direction, kReach, hit)) {
                    const uint8_t block = world.get(hit.x, hit.y, hit.z);
                    if (blockInfo(block).breakable) {
                        world.set(hit.x, hit.y, hit.z, Air);
                        things.debris({hit.x + 0.5f, hit.y + 0.5f, hit.z + 0.5f}, block, 4);
                    }
                }
                swing = 0.0f;
            }
            if (act.use) {
                const Ray ray = GetScreenToWorldRay(act.usePoint, camera);
                const Slot held = ui.held();
                RayHit hit;
                const bool onBlock = world.raycast(ray.position, ray.direction, kReach, hit);
                if (held.kind == Slot::BlockSlot) {
                    // The new block goes against the face that was hit.
                    const int x = hit.x + hit.nx, y = hit.y + hit.ny, z = hit.z + hit.nz;
                    const uint8_t there = onBlock ? world.get(x, y, z) : Stone;
                    if (onBlock && y >= 0 && y < kHeight && (there == Air || there == Water) &&
                        !player.overlapsBlock(x, y, z) && !thingInBlock(physics, x, y, z)) {
                        world.set(x, y, z, static_cast<uint8_t>(held.id));
                    }
                } else if (held.kind == Slot::ItemSlot) {
                    const Item item = static_cast<Item>(held.id);
                    if (item == Item::Domino && onBlock && hit.ny == 1) {
                        // Dominoes stand up where you tap, facing you: make a line and push the first.
                        const Vector3 at = Vector3Add(ray.position, Vector3Scale(ray.direction, hit.distance));
                        things.spawn(item, {at.x, hit.y + 1.26f, at.z}, {0, 0, 0}, player.yaw);
                    } else {
                        // Thrown from in front of the face, but never from inside a block.
                        float start = 0.9f;
                        auto inside = [&](float d) {
                            const Vector3 p = Vector3Add(ray.position, Vector3Scale(ray.direction, d));
                            return world.solid(cell(p.x), cell(p.y), cell(p.z));
                        };
                        while (start > 0.3f && inside(start)) start -= 0.1f;
                        const Vector3 at = Vector3Add(ray.position, Vector3Scale(ray.direction, start));
                        const Vector3 velocity = Vector3Add(Vector3Scale(ray.direction, 9.0f), {player.velocity.x, 1.5f, player.velocity.z});
                        things.spawn(item, at, velocity, player.yaw);
                    }
                } else {
                    things.push(ray.position, ray.direction, kReach);  // a punch
                }
                swing = 0.0f;
            }
            if (act.pick) {
                const Ray ray = GetScreenToWorldRay(act.pickPoint, camera);
                RayHit hit;
                if (world.raycast(ray.position, ray.direction, kReach, hit)) {
                    const uint8_t block = world.get(hit.x, hit.y, hit.z);
                    if (blockInfo(block).breakable) ui.pick(block, time);
                }
            }
            if (act.clearThings) things.clear();
            swing = std::min(1.0f, swing + dt / 0.25f);

            // --- World and physics ---------------------------------------------------------------
            world.update(player.position, player.forward());
            things.update(world, dt);
            for (const Things::Blast& blast : things.blasts) {  // a bomb that just went off shoves the player
                if (blast.age > 0.0f) continue;
                const Vector3 away = Vector3Subtract(player.eye(), blast.at);
                const float distance = Vector3Length(away);
                if (distance < 4.0f && distance > 0.01f) {
                    player.velocity = Vector3Add(player.velocity, Vector3Scale(away, (4.0f - distance) * 3.0f / distance));
                    player.onGround = false;
                }
            }
            b3World_Step(physics, dt, 4);

            // --- Drawing -------------------------------------------------------------------------
            BeginDrawing();
            ClearBackground(kSky);
            BeginMode3D(camera);
            world.draw(camera, player.forward());
            things.draw(world, static_cast<float>(time));
            RayHit hover;
            if (act.hasHover && !ui.inventoryOpen()) {
                const Ray ray = GetScreenToWorldRay(act.hover, camera);
                if (world.raycast(ray.position, ray.direction, kReach, hover)) {
                    DrawCubeWires({hover.x + 0.5f, hover.y + 0.5f, hover.z + 0.5f}, 1.004f, 1.004f, 1.004f, Fade(BLACK, 0.6f));
                }
            }
            for (Things::Blast& blast : things.blasts) {
                blast.age += dt;
                const float k = blast.age / 0.35f;
                DrawSphereEx(blast.at, 0.4f + 2.6f * k, 8, 10, Fade(Color{255, 200, 90, 255}, 0.8f * (1.0f - k)));
            }
            things.blasts.erase(std::remove_if(things.blasts.begin(), things.blasts.end(),
                                               [](const Things::Blast& b) { return b.age > 0.35f; }),
                                things.blasts.end());
            world.drawTranslucent(camera, player.forward());  // water and glass last, so what is behind shows
            ui.drawHand(world, player, swing);
            EndMode3D();

            const Vector3 eye = player.eye();
            if (world.get(cell(eye.x), cell(eye.y), cell(eye.z)) == Water) {
                DrawRectangle(0, 0, GetScreenWidth(), GetScreenHeight(), Color{30, 80, 160, 110});
            }
            ui.draw(world, player, things, time);
            EndDrawing();
        }
    }  // the world and the things go before the physics world they live in
    b3DestroyWorld(physics);
    CloseWindow();
    return 0;
}
