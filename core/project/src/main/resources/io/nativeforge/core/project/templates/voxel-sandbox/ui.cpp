// Touch controls, keyboard and mouse, the hotbar, the creative inventory and the first-person hand.
//
// Touch: the pad on the left walks (push it to the edge to run), drag anywhere on the world to look,
// tap the world to place the block or throw the item in your hand (or punch with an empty hand),
// hold a finger still on a block to break it. Jump: tap; double tap to fly, hold to go up. The
// small button next to it sneaks (or goes down while flying). The grid button opens the inventory.
// Keyboard and mouse: WASD walk, Space jumps (double tap flies), Shift sneaks, Ctrl runs, drag with
// the left button or use the arrows to look, left click breaks, right click places or throws,
// middle click picks a block, 1-9 or the wheel choose a slot, E opens the inventory, C clears.
#include "game.hpp"

#include "rlgl.h"

#include <algorithm>
#include <cmath>

namespace {
struct Pointer {
    int id;
    Vector2 position;
};

std::vector<Pointer> readPointers() {
    std::vector<Pointer> pointers;
    const int count = GetTouchPointCount();
    for (int i = 0; i < count; ++i) pointers.push_back({GetTouchPointId(i), GetTouchPosition(i)});
#if !defined(__ANDROID__)
    // On a computer the left mouse button is a finger too (id -1).
    if (count == 0 && IsMouseButtonDown(MOUSE_BUTTON_LEFT)) pointers.push_back({-1, GetMousePosition()});
#endif
    return pointers;
}

const char* slotName(Slot slot) {
    switch (slot.kind) {
        case Slot::BlockSlot: return blockInfo(static_cast<uint8_t>(slot.id)).name;
        case Slot::ItemSlot: return itemName(static_cast<Item>(slot.id));
        default: return "Hand";
    }
}

void drawText(const char* text, float x, float y, int size, Color color) {
    DrawText(text, static_cast<int>(x + 1), static_cast<int>(y + 1), size, Fade(BLACK, 0.5f * color.a / 255.0f));
    DrawText(text, static_cast<int>(x), static_cast<int>(y), size, color);
}

void drawCentred(const char* text, Vector2 centre, int size, Color color) {
    drawText(text, centre.x - MeasureText(text, size) / 2.0f, centre.y - size / 2.0f, size, color);
}

// An isometric cube with the block's textures: top, left and right faces, shaded.
void drawBlockIcon(const World& world, uint8_t block, Vector2 c, float size) {
    const float r = size * 0.42f, dx = r * 0.866f, dy = r * 0.5f;
    const Vector2 top{c.x, c.y - r}, left{c.x - dx, c.y - dy}, right{c.x + dx, c.y - dy}, mid = c;
    const Vector2 bottom{c.x, c.y + r}, bottomLeft{left.x, left.y + r}, bottomRight{right.x, right.y + r};
    struct Face { Vector2 p[4]; int face; unsigned char light; };
    // Corners in texture order: (0,0), (0,1), (1,1), (1,0).
    const Face faces[3] = {{{left, mid, right, top}, 0, 255}, {{left, bottomLeft, bottom, mid}, 1, 205}, {{mid, bottom, bottomRight, right}, 1, 160}};
    const unsigned char alpha = block == Water ? 190 : 255;
    rlDisableBackfaceCulling();
    rlSetTexture(world.atlas.id);
    rlBegin(RL_QUADS);
    for (const Face& f : faces) {
        Color tint = block == Water ? Color{70, 130, 210, alpha} : Color{255, 255, 255, alpha};
        tint = {static_cast<unsigned char>(tint.r * f.light / 255), static_cast<unsigned char>(tint.g * f.light / 255),
                static_cast<unsigned char>(tint.b * f.light / 255), alpha};
        static const float kST[4][2] = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        for (int i = 0; i < 4; ++i) {
            const Vector2 uv = blockUV(block, f.face, kST[i][0], kST[i][1]);
            rlColor4ub(tint.r, tint.g, tint.b, tint.a);
            rlTexCoord2f(uv.x, uv.y);
            rlVertex2f(f.p[i].x, f.p[i].y);
        }
    }
    rlEnd();
    rlSetTexture(0);
    rlEnableBackfaceCulling();
}

void drawHandIcon(Vector2 c, float s, float alpha) {
    const Color skin = Fade(Color{226, 172, 128, 255}, alpha), line = Fade(Color{150, 100, 70, 255}, alpha);
    DrawRectangleRounded({c.x - s * 0.2f, c.y - s * 0.1f, s * 0.4f, s * 0.36f}, 0.4f, 6, skin);  // palm
    for (int i = 0; i < 4; ++i) {  // fingers
        DrawRectangleRounded({c.x - s * 0.2f + i * s * 0.1f, c.y - s * 0.32f + (i == 0 || i == 3 ? s * 0.05f : 0), s * 0.09f, s * 0.26f},
                             0.5f, 4, skin);
    }
    DrawRectangleRounded({c.x - s * 0.33f, c.y - s * 0.02f, s * 0.16f, s * 0.1f}, 0.5f, 4, skin);  // thumb
    DrawLineEx({c.x - s * 0.12f, c.y + s * 0.1f}, {c.x + s * 0.12f, c.y + s * 0.1f}, std::max(1.0f, s * 0.02f), line);
}

void drawItemIcon(Item item, Vector2 c, float s) {
    const Color color = itemColor(item);
    switch (item) {
        case Item::Ball: DrawCircleV(c, s * 0.28f, color); break;
        case Item::Crate:
            DrawRectangleRec({c.x - s * 0.3f, c.y - s * 0.3f, s * 0.6f, s * 0.6f}, color);
            DrawRectangleRec({c.x - s * 0.3f, c.y - s * 0.06f, s * 0.6f, s * 0.12f}, ColorBrightness(color, -0.35f));
            break;
        case Item::Barrel:
            DrawRectangleRounded({c.x - s * 0.22f, c.y - s * 0.32f, s * 0.44f, s * 0.64f}, 0.4f, 6, color);
            DrawRectangleRec({c.x - s * 0.22f, c.y - s * 0.2f, s * 0.44f, s * 0.07f}, Color{70, 70, 78, 255});
            DrawRectangleRec({c.x - s * 0.22f, c.y + s * 0.13f, s * 0.44f, s * 0.07f}, Color{70, 70, 78, 255});
            break;
        case Item::BeachBall:
            DrawCircleV(c, s * 0.34f, color);
            for (int a = 0; a < 360; a += 120) DrawCircleSector(c, s * 0.34f, a, a + 60, 8, Color{236, 92, 72, 255});
            break;
        case Item::Plank: DrawRectanglePro({c.x, c.y, s * 0.7f, s * 0.14f}, {s * 0.35f, s * 0.07f}, -30, color); break;
        case Item::Bomb:
            DrawCircleV({c.x, c.y + s * 0.05f}, s * 0.25f, color);
            DrawLineEx({c.x + s * 0.1f, c.y - s * 0.15f}, {c.x + s * 0.22f, c.y - s * 0.32f}, s * 0.05f, Color{200, 180, 120, 255});
            DrawCircleV({c.x + s * 0.24f, c.y - s * 0.34f}, s * 0.05f, ORANGE);
            break;
        case Item::Domino:
            DrawRectangleRounded({c.x - s * 0.17f, c.y - s * 0.34f, s * 0.34f, s * 0.68f}, 0.2f, 4, color);
            DrawRectangleRec({c.x - s * 0.13f, c.y - s * 0.01f, s * 0.26f, s * 0.02f + 1}, RAYWHITE);
            DrawCircleV({c.x, c.y - s * 0.17f}, s * 0.05f, RAYWHITE);
            DrawCircleV({c.x - s * 0.08f, c.y + s * 0.1f}, s * 0.045f, RAYWHITE);
            DrawCircleV({c.x + s * 0.08f, c.y + s * 0.24f}, s * 0.045f, RAYWHITE);
            break;
        case Item::SteelBall:
            DrawCircleV(c, s * 0.3f, color);
            DrawCircleV({c.x - s * 0.1f, c.y - s * 0.1f}, s * 0.08f, Fade(WHITE, 0.8f));
            break;
        case Item::Cone:
            DrawTriangle({c.x, c.y - s * 0.34f}, {c.x - s * 0.24f, c.y + s * 0.26f}, {c.x + s * 0.24f, c.y + s * 0.26f}, color);
            DrawRectangleRec({c.x - s * 0.13f, c.y - s * 0.04f, s * 0.26f, s * 0.08f}, RAYWHITE);
            DrawRectangleRec({c.x - s * 0.3f, c.y + s * 0.26f, s * 0.6f, s * 0.07f}, ColorBrightness(color, -0.2f));
            break;
        case Item::Tyre:
            DrawRing(c, s * 0.16f, s * 0.34f, 0, 360, 20, color);
            DrawCircleV(c, s * 0.15f, Color{180, 182, 190, 255});
            break;
        case Item::Count: break;
    }
}
}  // namespace

void drawSlotIcon(const World& world, Slot slot, Vector2 centre, float size) {
    switch (slot.kind) {
        case Slot::BlockSlot: drawBlockIcon(world, static_cast<uint8_t>(slot.id), centre, size); break;
        case Slot::ItemSlot: drawItemIcon(static_cast<Item>(slot.id), centre, size); break;
        default: drawHandIcon(centre, size, 0.45f); break;
    }
}

// ---------------------------------------------------------------------------------------------
// Screen layout. Sizes are in units of 1/360 of the short side, so it fits any phone.

struct Ui::Layout {
    float unit, margin;
    Rectangle slots[kHotbarSlots], inventory, clear;
    Vector2 pad, jump, sneak;
    float padRadius, jumpRadius, sneakRadius;
    int text;
    // The inventory window.
    Rectangle panel, close;
    float cell;
    int columns;
    Vector2 grid;  // top left of the first cell
};

Ui::Layout Ui::layout() const {
    const float w = static_cast<float>(GetScreenWidth()), h = static_cast<float>(GetScreenHeight());
    Layout l{};
    l.unit = std::min(w, h) / 360.0f;
    const float u = l.unit;
    l.margin = 12 * u;
    l.text = std::max(10, static_cast<int>(13 * u));
    l.padRadius = 52 * u;
    l.jumpRadius = 32 * u;
    l.sneakRadius = 21 * u;
    // Nine slots and the inventory button, as big as fits.
    const float gap = 3 * u;
    const float slot = std::min(40 * u, (w - 2 * l.margin - 10 * gap) / 10.5f);
    const float barWidth = 10 * slot + 10 * gap;
    const float barX = (w - barWidth) / 2, barY = h - l.margin - slot;
    for (int i = 0; i < kHotbarSlots; ++i) l.slots[i] = {barX + i * (slot + gap), barY, slot, slot};
    l.inventory = {barX + kHotbarSlots * (slot + gap) + gap, barY, slot, slot};
    // Wide screens: the controls sit beside the hotbar. Narrow ones: above it.
    const float sides = 2 * (l.margin + 2 * l.padRadius + 8 * u);
    const float bottom = w - sides >= barWidth ? h - l.margin : barY - 10 * u;
    l.pad = {l.margin + l.padRadius + 4 * u, bottom - l.padRadius};
    l.jump = {w - l.margin - l.jumpRadius - 4 * u, bottom - l.jumpRadius - 2 * u};
    l.sneak = {l.jump.x - l.jumpRadius - l.sneakRadius - 14 * u, l.jump.y + l.jumpRadius - l.sneakRadius};
    l.clear = {w - l.margin - 60 * u, l.margin, 60 * u, 26 * u};

    // Inventory: a grid of everything, above the hotbar.
    l.cell = 40 * u;
    const float cellGap = 4 * u;
    const float panelW = std::min(w - 2 * l.margin, 12 * (l.cell + cellGap) + 24 * u);
    l.columns = std::max(1, static_cast<int>((panelW - 16 * u) / (l.cell + cellGap)));
    const int rows = (static_cast<int>(catalogue_.size()) + l.columns - 1) / l.columns;
    const float panelH = std::min(barY - 2 * l.margin, 34 * u + rows * (l.cell + cellGap) + 10 * u);
    l.panel = {(w - panelW) / 2, std::max(l.margin, (barY - 8 * u - panelH) / 2), panelW, panelH};
    l.close = {l.panel.x + l.panel.width - 30 * u, l.panel.y + 4 * u, 26 * u, 26 * u};
    const float gridWidth = l.columns * (l.cell + cellGap) - cellGap;
    l.grid = {l.panel.x + (l.panel.width - gridWidth) / 2, l.panel.y + 34 * u};
    return l;
}

Ui::Ui() {
    catalogue_.push_back({});  // the bare hand
    for (int b = Grass; b < BlockCount; ++b) {
        if (b != Basalt) catalogue_.push_back({Slot::BlockSlot, b});  // basalt could never be broken again
    }
    for (int i = 0; i < static_cast<int>(Item::Count); ++i) catalogue_.push_back({Slot::ItemSlot, i});
    const Slot start[kHotbarSlots] = {{},
                                      {Slot::BlockSlot, Planks},
                                      {Slot::BlockSlot, Cobblestone},
                                      {Slot::BlockSlot, Glass},
                                      {Slot::BlockSlot, Log},
                                      {Slot::ItemSlot, static_cast<int>(Item::Ball)},
                                      {Slot::ItemSlot, static_cast<int>(Item::Crate)},
                                      {Slot::ItemSlot, static_cast<int>(Item::Bomb)},
                                      {Slot::ItemSlot, static_cast<int>(Item::Domino)}};
    std::copy(start, start + kHotbarSlots, hotbar_);
}

void Ui::say(const std::string& text, double time) {
    toast_ = text;
    toastUntil_ = time + 1.6;
}

void Ui::pick(uint8_t block, double time) {
    for (int i = 0; i < kHotbarSlots; ++i) {
        if (hotbar_[i].kind == Slot::BlockSlot && hotbar_[i].id == block) {
            selected_ = i;
            say(slotName(hotbar_[i]), time);
            return;
        }
    }
    hotbar_[selected_] = {Slot::BlockSlot, block};
    say(slotName(hotbar_[selected_]), time);
}

Actions Ui::update(Player& player, float dt, double time) {
    Actions a;
    const Layout l = layout();
    const float lookSpeed = 0.006f / l.unit;  // radians per pixel, the same feel on every screen
    const Vector2 centre{GetScreenWidth() / 2.0f, GetScreenHeight() / 2.0f};
    auto cellAt = [&](Vector2 p) {
        for (int i = 0; i < static_cast<int>(catalogue_.size()); ++i) {
            const Rectangle r{l.grid.x + (i % l.columns) * (l.cell + 4 * l.unit), l.grid.y + (i / l.columns) * (l.cell + 4 * l.unit), l.cell, l.cell};
            if (r.y + r.height <= l.panel.y + l.panel.height && CheckCollisionPointRec(p, r)) return i;
        }
        return -1;
    };
    auto choose = [&](int slot) {
        if (slot != selected_) say(slotName(hotbar_[slot]), time);
        selected_ = slot;
    };

    jumpHeld_ = sneakHeld_ = false;
    pad_ = {0, 0};
    bool jumpPressed = false;
    for (Finger& f : fingers_) f.alive = false;
    for (const Pointer& p : readPointers()) {
        auto it = std::find_if(fingers_.begin(), fingers_.end(), [&](const Finger& f) { return f.id == p.id; });
        if (it == fingers_.end()) {  // a new finger: what did it land on?
            Finger f;
            f.id = p.id;
            f.start = f.last = p.position;
            f.pressedAt = time;
            f.role = Finger::Button;
            int slot = -1;
            for (int i = 0; i < kHotbarSlots; ++i) {
                if (CheckCollisionPointRec(p.position, l.slots[i])) slot = i;
            }
            if (slot >= 0) {
                choose(slot);
            } else if (CheckCollisionPointRec(p.position, l.inventory)) {
                inventoryOpen_ = !inventoryOpen_;
            } else if (inventoryOpen_) {
                const int cell = cellAt(p.position);
                if (cell >= 0) {
                    hotbar_[selected_] = catalogue_[cell];
                    say(slotName(catalogue_[cell]), time);
                } else if (CheckCollisionPointRec(p.position, l.close) || !CheckCollisionPointRec(p.position, l.panel)) {
                    inventoryOpen_ = false;
                }
            } else if (CheckCollisionPointRec(p.position, l.clear)) {
                a.clearThings = true;
            } else if (Vector2Distance(p.position, l.jump) <= l.jumpRadius * 1.25f) {
                f.role = Finger::Jump;
                jumpPressed = true;
            } else if (Vector2Distance(p.position, l.sneak) <= l.sneakRadius * 1.3f) {
                f.role = Finger::Sneak;
                if (!player.flying) sneakToggle_ = !sneakToggle_;
            } else if (Vector2Distance(p.position, l.pad) <= l.padRadius * 1.5f) {
                f.role = Finger::Pad;
            } else {
                f.role = Finger::World;
            }
            fingers_.push_back(f);
            it = fingers_.end() - 1;
        }
        Finger& f = *it;
        f.alive = true;
        switch (f.role) {
            case Finger::World:
                if (!f.breaking && Vector2Distance(p.position, f.start) > 12 * l.unit) f.moved = true;
                if (f.moved) {  // dragging looks around
                    player.yaw -= (p.position.x - f.last.x) * lookSpeed;
                    player.pitch = Clamp(player.pitch - (p.position.y - f.last.y) * lookSpeed, -1.55f, 1.55f);
                } else if (time - f.pressedAt >= 0.3) {  // held still: break, again every quarter second
                    f.breaking = true;
                    if (time - f.lastBreak >= 0.25) {
                        a.breakBlock = true;
                        a.breakPoint = p.position;
                        f.lastBreak = time;
                    }
                }
                if (f.breaking) {
                    a.hasHover = true;
                    a.hover = p.position;
                }
                break;
            case Finger::Pad: {
                const Vector2 raw = Vector2Scale(Vector2Subtract(p.position, l.pad), 1.0f / l.padRadius);
                const Vector2 offset = Vector2Length(raw) > 1.0f ? Vector2Normalize(raw) : raw;
                a.input.move = {offset.x, -offset.y};
                a.input.sprint = Vector2Length(raw) > 1.2f && offset.y < -0.5f;  // pushed past the top edge
                a.input.autoJump = f.id != -1;
                pad_ = offset;
                break;
            }
            case Finger::Jump: jumpHeld_ = true; break;
            case Finger::Sneak: sneakHeld_ = true; break;
            case Finger::Button: break;
        }
        f.last = p.position;
    }
    for (const Finger& f : fingers_) {  // a quick tap on the world: use (touch) or break (mouse)
        if (f.alive || f.role != Finger::World || f.moved || f.breaking || time - f.pressedAt >= 0.3) continue;
        if (f.id == -1) {
            a.breakBlock = true;
            a.breakPoint = f.last;
        } else {
            a.use = true;
            a.usePoint = f.last;
        }
    }
    fingers_.erase(std::remove_if(fingers_.begin(), fingers_.end(), [](const Finger& f) { return !f.alive; }), fingers_.end());

    // Keyboard and mouse.
    Vector2 keys{0, 0};
    if (IsKeyDown(KEY_W)) keys.y += 1;
    if (IsKeyDown(KEY_S)) keys.y -= 1;
    if (IsKeyDown(KEY_D)) keys.x += 1;
    if (IsKeyDown(KEY_A)) keys.x -= 1;
    if (keys.x != 0 || keys.y != 0) {
        a.input.move = Vector2Normalize(keys);
        a.input.autoJump = false;
    }
    if (IsKeyDown(KEY_LEFT_CONTROL) || IsKeyDown(KEY_RIGHT_CONTROL)) a.input.sprint = true;
    if (IsKeyDown(KEY_LEFT)) player.yaw += 2.2f * dt;
    if (IsKeyDown(KEY_RIGHT)) player.yaw -= 2.2f * dt;
    if (IsKeyDown(KEY_UP)) player.pitch = std::min(1.55f, player.pitch + 1.6f * dt);
    if (IsKeyDown(KEY_DOWN)) player.pitch = std::max(-1.55f, player.pitch - 1.6f * dt);
    a.input.jumpHeld = jumpHeld_ || IsKeyDown(KEY_SPACE);
    a.input.jumpPressed = jumpPressed || IsKeyPressed(KEY_SPACE);
    const bool shift = IsKeyDown(KEY_LEFT_SHIFT) || IsKeyDown(KEY_RIGHT_SHIFT);
    a.input.sneak = shift || (player.flying ? sneakHeld_ : sneakToggle_);
    if (player.flying) sneakToggle_ = false;
    if (IsKeyPressed(KEY_E)) inventoryOpen_ = !inventoryOpen_;
    for (int i = 0; i < kHotbarSlots; ++i) {
        if (IsKeyPressed(KEY_ONE + i)) choose(i);
    }
    const float wheel = GetMouseWheelMove();
    if (wheel != 0) choose((selected_ + (wheel < 0 ? 1 : kHotbarSlots - 1)) % kHotbarSlots);
    if (IsKeyPressed(KEY_C)) a.clearThings = true;
    // Keys that act on the block in the middle of the screen.
    if (IsKeyPressed(KEY_F) || IsKeyPressed(KEY_ENTER)) {
        a.use = true;
        a.usePoint = centre;
    }
    if (IsKeyPressed(KEY_R)) {
        a.breakBlock = true;
        a.breakPoint = centre;
    }
#if !defined(__ANDROID__)
    const Vector2 mouse = GetMousePosition();
    bool overControls = CheckCollisionPointRec(mouse, l.inventory) || CheckCollisionPointRec(mouse, l.clear);
    for (const Rectangle& r : l.slots) overControls |= CheckCollisionPointRec(mouse, r);
    if (!overControls) {
        if (IsMouseButtonPressed(MOUSE_BUTTON_RIGHT)) {
            a.use = true;
            a.usePoint = mouse;
        }
        if (IsMouseButtonPressed(MOUSE_BUTTON_MIDDLE)) {
            a.pick = true;
            a.pickPoint = mouse;
        }
        if (!a.hasHover && GetTouchPointCount() == 0) {
            a.hasHover = true;
            a.hover = mouse;
        }
    }
#endif
    if (!a.hasHover) {  // touch: the cross in the middle
        a.hasHover = true;
        a.hover = centre;
    }
    if (inventoryOpen_) {  // the world waits while the inventory is open
        const bool jump = a.input.jumpHeld;
        a = Actions{};
        a.input.jumpHeld = jump && player.flying;
    }
    return a;
}

void Ui::draw(const World& world, const Player& player, const Things& things, double time) const {
    const Layout l = layout();
    const float u = l.unit, w = static_cast<float>(GetScreenWidth()), h = static_cast<float>(GetScreenHeight());

    // Cross in the middle.
    const float cross = 8 * u, thick = std::max(2.0f, 2 * u);
    DrawRectangleV({w / 2 - cross, h / 2 - thick / 2}, {2 * cross, thick}, Fade(WHITE, 0.8f));
    DrawRectangleV({w / 2 - thick / 2, h / 2 - cross}, {thick, 2 * cross}, Fade(WHITE, 0.8f));

    // Move pad.
    DrawCircleV(l.pad, l.padRadius, Fade(BLACK, 0.22f));
    DrawCircleLinesV(l.pad, l.padRadius, Fade(WHITE, 0.5f));
    const Vector2 knob = Vector2Add(l.pad, Vector2Scale(pad_, l.padRadius * 0.6f));
    DrawCircleV(knob, 21 * u, Fade(WHITE, 0.55f));

    // Jump (up while flying) and sneak (down while flying).
    DrawCircleV(l.jump, l.jumpRadius, Fade(jumpHeld_ ? WHITE : BLACK, jumpHeld_ ? 0.45f : 0.25f));
    DrawCircleLinesV(l.jump, l.jumpRadius, Fade(WHITE, 0.6f));
    const float a = 11 * u;
    DrawTriangle({l.jump.x, l.jump.y - a}, {l.jump.x - a, l.jump.y + a * 0.6f}, {l.jump.x + a, l.jump.y + a * 0.6f}, Fade(WHITE, 0.85f));
    if (player.flying) drawCentred("fly", {l.jump.x, l.jump.y + l.jumpRadius * 0.62f}, l.text * 3 / 4, Fade(WHITE, 0.9f));
    const bool sneakOn = player.flying ? sneakHeld_ : sneakToggle_;
    DrawCircleV(l.sneak, l.sneakRadius, Fade(sneakOn ? WHITE : BLACK, sneakOn ? 0.45f : 0.25f));
    DrawCircleLinesV(l.sneak, l.sneakRadius, Fade(WHITE, 0.6f));
    const float b = 7 * u;
    for (int i = 0; i < 2; ++i) {  // a double chevron pointing down
        const float y = l.sneak.y - b * 0.6f + i * b * 0.8f;
        DrawLineEx({l.sneak.x - b, y}, {l.sneak.x, y + b * 0.6f}, std::max(2.0f, 2.2f * u), Fade(WHITE, 0.85f));
        DrawLineEx({l.sneak.x, y + b * 0.6f}, {l.sneak.x + b, y}, std::max(2.0f, 2.2f * u), Fade(WHITE, 0.85f));
    }

    // Hotbar and the inventory button.
    for (int i = 0; i < kHotbarSlots; ++i) {
        const Rectangle r = l.slots[i];
        const bool selected = i == selected_;
        DrawRectangleRec(r, Fade(BLACK, selected ? 0.5f : 0.3f));
        DrawRectangleLinesEx(r, selected ? 3 * u : u, Fade(WHITE, selected ? 1.0f : 0.5f));
        drawSlotIcon(world, hotbar_[i], {r.x + r.width / 2, r.y + r.height / 2}, r.width * 0.8f);
        DrawText(TextFormat("%d", i + 1), static_cast<int>(r.x + 3 * u), static_cast<int>(r.y + 2 * u), l.text * 2 / 3, Fade(WHITE, 0.7f));
    }
    const Rectangle inv = l.inventory;
    DrawRectangleRec(inv, Fade(inventoryOpen_ ? WHITE : BLACK, inventoryOpen_ ? 0.4f : 0.3f));
    DrawRectangleLinesEx(inv, u, Fade(WHITE, 0.5f));
    for (int i = 0; i < 9; ++i) {  // a 3 x 3 grid of dots: "everything"
        const float s = inv.width * 0.16f, gap = inv.width * 0.06f;
        const float x0 = inv.x + (inv.width - 3 * s - 2 * gap) / 2, y0 = inv.y + (inv.height - 3 * s - 2 * gap) / 2;
        DrawRectangleRec({x0 + (i % 3) * (s + gap), y0 + (i / 3) * (s + gap), s, s}, Fade(WHITE, 0.85f));
    }

    // The name of what is in the hand, for a moment after it changes.
    if (time < toastUntil_) {
        const float fade = static_cast<float>(std::min(1.0, (toastUntil_ - time) * 3));
        drawCentred(toast_.c_str(), {w / 2, l.slots[0].y - l.text - 4 * u}, l.text, Fade(WHITE, fade));
    }

    // Top bar: status and Clear.
    const char* mode = player.flying ? "Flying" : player.inWater ? "Swimming" : player.sneaking ? "Sneaking" : "Walking";
    const std::string first = std::string("FPS ") + std::to_string(GetFPS()) + "   " + mode;
    const std::string second = std::string("XYZ ") + std::to_string(static_cast<int>(std::floor(player.position.x))) + " " +
                               std::to_string(static_cast<int>(std::floor(player.position.y))) + " " +
                               std::to_string(static_cast<int>(std::floor(player.position.z))) + "   Things " +
                               std::to_string(things.count()) + "   Chunks " + std::to_string(world.chunkCount());
    const std::string both = first + "   " + second;
    if (MeasureText(both.c_str(), l.text) < l.clear.x - 2 * l.margin) {
        drawText(both.c_str(), l.margin, l.margin, l.text, WHITE);
    } else {  // narrow screen: two lines
        drawText(first.c_str(), l.margin, l.margin, l.text, WHITE);
        drawText(second.c_str(), l.margin, l.margin + l.text * 1.3f, l.text, WHITE);
    }
    DrawRectangleRounded(l.clear, 0.3f, 6, Fade(BLACK, 0.3f));
    drawCentred("Clear", {l.clear.x + l.clear.width / 2, l.clear.y + l.clear.height / 2}, l.text, WHITE);

    if (!inventoryOpen_) return;
    // Creative inventory: every block and item; a tap puts it in the selected slot.
    DrawRectangleRec({0, 0, w, h}, Fade(BLACK, 0.35f));
    DrawRectangleRounded(l.panel, 0.04f, 6, Color{38, 42, 52, 235});
    DrawRectangleRoundedLinesEx(l.panel, 0.04f, 6, u, Fade(WHITE, 0.4f));
    drawText("Creative inventory", l.panel.x + 12 * u, l.panel.y + 10 * u, l.text, WHITE);
    DrawRectangleRounded(l.close, 0.3f, 4, Fade(WHITE, 0.15f));
    drawCentred("X", {l.close.x + l.close.width / 2, l.close.y + l.close.height / 2}, l.text, WHITE);
    const Slot held = hotbar_[selected_];
    for (int i = 0; i < static_cast<int>(catalogue_.size()); ++i) {
        const Rectangle r{l.grid.x + (i % l.columns) * (l.cell + 4 * u), l.grid.y + (i / l.columns) * (l.cell + 4 * u), l.cell, l.cell};
        if (r.y + r.height > l.panel.y + l.panel.height) break;
        const Slot s = catalogue_[i];
        const bool inHand = s.kind == held.kind && (s.kind == Slot::Empty || s.id == held.id);
        DrawRectangleRec(r, Fade(BLACK, inHand ? 0.55f : 0.3f));
        DrawRectangleLinesEx(r, inHand ? 2 * u : u, Fade(WHITE, inHand ? 0.9f : 0.25f));
        if (s.kind == Slot::Empty) drawHandIcon({r.x + r.width / 2, r.y + r.height / 2}, r.width * 0.8f, 1.0f);
        else drawSlotIcon(world, s, {r.x + r.width / 2, r.y + r.height / 2}, r.width * 0.8f);
    }
}

// The hand, or what it holds, in the lower right corner of the view. Drawn in the world a hand's
// length in front of the eyes, inside the player's own box, so it never sinks into a wall.
void Ui::drawHand(const World& world, const Player& player, float swing) const {
    const Vector3 forward = player.look();
    Vector3 right = Vector3Normalize(Vector3CrossProduct(forward, {0, 1, 0}));
    if (Vector3Length(right) < 0.5f) right = {std::cos(player.yaw), 0, -std::sin(player.yaw)};
    const Vector3 up = Vector3CrossProduct(right, forward);
    const Vector3 back = Vector3Negate(forward);
    // A little bob while walking, and an arc while swinging.
    const float speed = std::sqrt(player.velocity.x * player.velocity.x + player.velocity.z * player.velocity.z);
    const float t = static_cast<float>(GetTime());
    const float bob = player.onGround ? std::min(speed / 4.3f, 1.0f) : 0.0f;
    const float arc = std::sin(swing * PI);
    const float low = hotbar_[selected_].kind == Slot::Empty ? 0.11f : 0.08f;  // the arm reaches up from below
    // On a tall (portrait) screen the view is narrow: keep the hand inside it.
    const float aspect = static_cast<float>(GetScreenWidth()) / std::max(1, GetScreenHeight());
    const float side = std::min(0.115f, 0.2f * std::tan(35 * DEG2RAD) * aspect * 0.7f);
    const Vector3 at = Vector3Add(player.eye(), Vector3Add(Vector3Add(Vector3Scale(forward, 0.2f - arc * 0.03f),
                                                                       Vector3Scale(right, side + std::sin(t * 9) * 0.006f * bob)),
                                                           Vector3Scale(up, -low - std::fabs(std::cos(t * 9)) * 0.006f * bob - arc * 0.02f)));
    // Columns: local x = right, y = up, z = back (so the hand points along -z).
    const Matrix basis{right.x, up.x, back.x, at.x, right.y, up.y, back.y, at.y, right.z, up.z, back.z, at.z, 0, 0, 0, 1};
    const Vector3 light{Vector3DotProduct(kSun, right), Vector3DotProduct(kSun, up), Vector3DotProduct(kSun, back)};

    rlDrawRenderBatchActive();
    rlPushMatrix();
    rlMultMatrixf(MatrixToFloat(basis));
    rlRotatef(-arc * 35, 1, 0, 0);  // the swing tips the hand forward
    const Slot slot = hotbar_[selected_];
    if (slot.kind == Slot::BlockSlot) {
        rlRotatef(30, 0, 1, 0);
        drawBlockCube(world, static_cast<uint8_t>(slot.id), 0.028f, light);
    } else if (slot.kind == Slot::ItemSlot) {
        rlScalef(0.095f, 0.095f, 0.095f);
        rlRotatef(25, 0, 1, 0);
        drawItem(static_cast<Item>(slot.id), itemColor(static_cast<Item>(slot.id)), 2.5f, t, light);
    } else {
        // The arm: a sleeve and a hand, reaching forward and a little up.
        rlRotatef(12, 1, 0, 0);
        rlRotatef(-8, 0, 1, 0);
        rlTranslatef(0, 0, 0.09f);
        drawBox({0.026f, 0.026f, 0.1f}, Color{226, 172, 128, 255}, light);
        rlTranslatef(0, 0, 0.12f);
        drawBox({0.03f, 0.03f, 0.05f}, Color{60, 110, 170, 255}, light);
    }
    rlPopMatrix();
}
