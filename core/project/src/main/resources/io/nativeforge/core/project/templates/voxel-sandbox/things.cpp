// Things you throw, and the bits that fly off broken blocks. Each one is a Box3D body drawn with a
// few lit triangles. Things float in water, bombs blow up blocks, and things far from the player
// wait (disabled) until the ground under them has a collider again.
#include "game.hpp"

#include "rlgl.h"

#include <algorithm>
#include <cmath>

// Light for the things, from the same side as the brighter faces of the terrain.
const Vector3 kSun = Vector3Normalize({0.45f, 1.0f, 0.3f});

namespace {
constexpr int kMaxThings = 160, kMaxDebris = 48;

const char* const kItemNames[static_cast<int>(Item::Count)] = {
    "Ball", "Crate", "Barrel", "Beach ball", "Plank", "Bomb", "Domino", "Steel ball", "Cone", "Tyre"};

int cell(float v) { return static_cast<int>(std::floor(v)); }

Color lit(Color c, Vector3 normal, Vector3 light) {
    const float k = 0.5f + 0.5f * std::max(0.0f, Vector3DotProduct(normal, light));
    return {static_cast<unsigned char>(c.r * k), static_cast<unsigned char>(c.g * k), static_cast<unsigned char>(c.b * k), c.a};
}

void litVertex(Vector3 p, Vector3 n, Color c, Vector3 light) {
    const Color k = lit(c, n, light);
    rlColor4ub(k.r, k.g, k.b, k.a);
    rlVertex3f(p.x, p.y, p.z);
}

// A sphere; with a second colour, alternate slices take it (a beach ball).
void drawSphere(float radius, Color color, Color stripes, Vector3 light) {
    constexpr int kRings = 8, kSlices = 12;
    auto point = [](int ring, int slice) {
        const float theta = PI * ring / kRings, phi = 2 * PI * slice / kSlices;
        return Vector3{std::sin(theta) * std::cos(phi), std::cos(theta), std::sin(theta) * std::sin(phi)};
    };
    rlBegin(RL_TRIANGLES);
    for (int ring = 0; ring < kRings; ++ring) {
        for (int slice = 0; slice < kSlices; ++slice) {
            const Color c = slice % 2 ? stripes : color;
            const Vector3 a = point(ring, slice), b = point(ring + 1, slice);
            const Vector3 d = point(ring + 1, slice + 1), e = point(ring, slice + 1);
            for (Vector3 n : {a, d, b, a, e, d}) litVertex(Vector3Scale(n, radius), n, c, light);
        }
    }
    rlEnd();
}

// A cylinder or a cone along y, from radius r0 at the bottom to r1 at the top. bands colours the
// rings near the ends; the caps take it too.
void drawRound(float r0, float r1, float halfHeight, Color color, Color bands, float bandSize, Vector3 light, int sides = 12) {
    rlBegin(RL_TRIANGLES);
    const float slope = (r0 - r1) / (2 * halfHeight);  // the side normal leans up on a cone
    for (int i = 0; i < sides; ++i) {
        const float a0 = 2 * PI * i / sides, a1 = 2 * PI * (i + 1) / sides;
        const Vector3 n0 = Vector3Normalize({std::cos(a0), slope, std::sin(a0)});
        const Vector3 n1 = Vector3Normalize({std::cos(a1), slope, std::sin(a1)});
        const float ys[4] = {-halfHeight, -halfHeight * (1 - bandSize), halfHeight * (1 - bandSize), halfHeight};
        for (int r = 0; r < 3; ++r) {
            const Color c = r == 1 ? color : bands;
            const float ra = Lerp(r0, r1, (ys[r] + halfHeight) / (2 * halfHeight));
            const float rb = Lerp(r0, r1, (ys[r + 1] + halfHeight) / (2 * halfHeight));
            const Vector3 p00{std::cos(a0) * ra, ys[r], std::sin(a0) * ra}, p01{std::cos(a0) * rb, ys[r + 1], std::sin(a0) * rb};
            const Vector3 p10{std::cos(a1) * ra, ys[r], std::sin(a1) * ra}, p11{std::cos(a1) * rb, ys[r + 1], std::sin(a1) * rb};
            litVertex(p00, n0, c, light);
            litVertex(p01, n0, c, light);
            litVertex(p11, n1, c, light);
            litVertex(p00, n0, c, light);
            litVertex(p11, n1, c, light);
            litVertex(p10, n1, c, light);
        }
        for (int s = -1; s <= 1; s += 2) {  // caps
            const float r = s > 0 ? r1 : r0;
            const Vector3 up{0, static_cast<float>(s), 0}, centre{0, s * halfHeight, 0};
            const Vector3 p0{std::cos(a0) * r, s * halfHeight, std::sin(a0) * r}, p1{std::cos(a1) * r, s * halfHeight, std::sin(a1) * r};
            litVertex(centre, up, bands, light);
            litVertex(s > 0 ? p1 : p0, up, bands, light);
            litVertex(s > 0 ? p0 : p1, up, bands, light);
        }
    }
    rlEnd();
}

// Box3D hull from points; the hull keeps every point here.
b3HullData* hullFrom(const std::vector<b3Vec3>& points) {
    return b3CreateHull(points.data(), static_cast<int>(points.size()), static_cast<int>(points.size()));
}

// Looks for water at the point, and returns the height of its surface (or -1).
float waterSurface(const World& world, Vector3 p) {
    int x = cell(p.x), y = cell(p.y), z = cell(p.z);
    if (world.get(x, y, z) != Water) return -1.0f;
    while (y < kHeight && world.get(x, y, z) == Water) ++y;
    return y - 0.12f;  // the drawn surface sits a little below the block's top
}

// A soft dark disc on the ground under a thing, so you can tell where it is in the air.
void drawShadow(const World& world, Vector3 p, float radius) {
    const int x = cell(p.x), z = cell(p.z);
    int y = std::min(cell(p.y), kHeight - 1);
    const int lowest = y - 9;
    while (y > lowest && !world.solid(x, y, z)) --y;
    if (y <= lowest) return;
    const float ground = y + 1.02f, height = p.y - ground;
    if (height > 8.0f || height < -0.5f) return;
    const float r = radius * (1.1f - height * 0.08f);
    const unsigned char alpha = static_cast<unsigned char>(100 * (1.0f - height / 8.0f));
    rlBegin(RL_TRIANGLES);
    constexpr int kSegments = 12;
    for (int i = 0; i < kSegments; ++i) {
        const float a0 = 2 * PI * i / kSegments, a1 = 2 * PI * (i + 1) / kSegments;
        rlColor4ub(0, 0, 0, alpha);
        rlVertex3f(p.x, ground, p.z);
        rlVertex3f(p.x + std::cos(a1) * r, ground, p.z + std::sin(a1) * r);
        rlVertex3f(p.x + std::cos(a0) * r, ground, p.z + std::sin(a0) * r);
    }
    rlEnd();
}
}  // namespace

const char* itemName(Item item) { return kItemNames[static_cast<int>(item)]; }

Color itemColor(Item item) {
    static const Color kColors[static_cast<int>(Item::Count)] = {
        {230, 82, 70, 255}, {176, 128, 72, 255}, {190, 60, 50, 255}, {245, 245, 240, 255}, {201, 160, 104, 255},
        {48, 50, 58, 255},  {30, 30, 36, 255},   {170, 176, 186, 255}, {245, 120, 30, 255}, {38, 38, 42, 255}};
    return kColors[static_cast<int>(item)];
}

void drawBox(Vector3 half, Color color, Vector3 light) {
    rlBegin(RL_TRIANGLES);
    for (int axis = 0; axis < 3; ++axis) {
        for (int sign = -1; sign <= 1; sign += 2) {
            const int u = (axis + 1) % 3, v = (axis + 2) % 3;
            Vector3 corners[4];
            static const int kUV[4][2] = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int i = 0; i < 4; ++i) {
                float p[3], h[3] = {half.x, half.y, half.z};
                p[axis] = sign * h[axis];
                p[u] = kUV[i][0] * h[u];
                p[v] = kUV[i][1] * h[v];
                corners[i] = {p[0], p[1], p[2]};
            }
            float n[3] = {0, 0, 0};
            n[axis] = static_cast<float>(sign);
            const Vector3 normal{n[0], n[1], n[2]};
            const int order[6] = {0, 1, 2, 0, 2, 3}, back[6] = {0, 2, 1, 0, 3, 2};
            for (int i : sign > 0 ? order : back) litVertex(corners[i], normal, color, light);
        }
    }
    rlEnd();
}

// A cube with the block's textures, centred on the current matrix.
void drawBlockCube(const World& world, uint8_t block, float half, Vector3 light) {
    rlSetTexture(world.atlas.id);
    rlBegin(RL_TRIANGLES);
    const Color tint = block == Water ? Color{70, 130, 210, 200} : WHITE;
    for (int axis = 0; axis < 3; ++axis) {
        for (int sign = -1; sign <= 1; sign += 2) {
            const int face = axis == 1 ? (sign > 0 ? 0 : 2) : 1;
            float n[3] = {0, 0, 0};
            n[axis] = static_cast<float>(sign);
            const Vector3 normal{n[0], n[1], n[2]};
            // Corners with the texture upright on the sides: s along the face, t down.
            Vector3 corners[4];
            Vector2 uv[4];
            static const float kST[4][2] = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int i = 0; i < 4; ++i) {
                const float s = kST[i][0], t = kST[i][1];
                float p[3];
                p[axis] = sign * half;
                if (axis == 1) {
                    p[0] = (s * 2 - 1) * half;
                    p[2] = (t * 2 - 1) * half * sign;
                } else {
                    const int along = axis == 0 ? 2 : 0;
                    const float dir = (axis == 0 ? -sign : sign);
                    p[along] = (s * 2 - 1) * half * dir;
                    p[1] = (1 - t * 2) * half;
                }
                corners[i] = {p[0], p[1], p[2]};
                uv[i] = blockUV(block, face, s, t);
            }
            const Color c = lit(tint, normal, light);
            // Counter-clockwise from outside: the order depends on how s and t run on this face.
            const Vector3 e1 = Vector3Subtract(corners[1], corners[0]), e2 = Vector3Subtract(corners[2], corners[0]);
            const bool flip = Vector3DotProduct(Vector3CrossProduct(e1, e2), normal) < 0;
            const int order[6] = {0, 1, 2, 0, 2, 3}, back[6] = {0, 2, 1, 0, 3, 2};
            for (int i : flip ? back : order) {
                rlColor4ub(c.r, c.g, c.b, c.a);
                rlTexCoord2f(uv[i].x, uv[i].y);
                rlVertex3f(corners[i].x, corners[i].y, corners[i].z);
            }
        }
    }
    rlEnd();
    rlSetTexture(0);
}

// One item's model, centred on the current matrix. light is the sun seen from the item.
void drawItem(Item item, Color color, float fuse, float time, Vector3 light) {
    switch (item) {
        case Item::Ball: drawSphere(0.25f, color, ColorBrightness(color, -0.12f), light); break;
        case Item::Crate:
            drawBox({0.35f, 0.35f, 0.35f}, color, light);
            drawBox({0.36f, 0.06f, 0.36f}, ColorBrightness(color, -0.35f), light);  // a strap around it
            break;
        case Item::Barrel: drawRound(0.3f, 0.3f, 0.45f, color, Color{70, 70, 78, 255}, 0.4f, light); break;
        case Item::BeachBall: drawSphere(0.5f, color, Color{236, 92, 72, 255}, light); break;
        case Item::Plank: drawBox({0.9f, 0.07f, 0.22f}, color, light); break;
        case Item::Bomb: {
            // Blinks faster as the fuse burns down.
            const bool blink = std::fmod(time * (8.0f - 2.5f * fuse), 1.0f) < 0.5f;
            drawSphere(0.22f, blink ? Color{200, 50, 40, 255} : color, color, light);
            rlPushMatrix();
            rlTranslatef(0, 0.24f, 0);
            drawBox({0.04f, 0.06f, 0.04f}, Color{200, 180, 120, 255}, light);
            rlPopMatrix();
            break;
        }
        case Item::Domino:
            // Thin along z: placed facing the player, a line of them topples away from it.
            rlPushMatrix();
            rlScalef(0.625f, 0.625f, 0.625f);
            drawBox({0.2f, 0.4f, 0.05f}, color, light);
            for (int side = -1; side <= 1; side += 2) {  // pips on both faces, and the line between halves
                for (Vector3 pip : {Vector3{0, 0.22f, 0}, Vector3{-0.08f, -0.15f, 0}, Vector3{0.08f, -0.29f, 0}}) {
                    rlPushMatrix();
                    rlTranslatef(pip.x * side, pip.y, side * 0.05f);
                    drawBox({0.035f, 0.035f, 0.006f}, Color{240, 240, 236, 255}, light);
                    rlPopMatrix();
                }
                rlPushMatrix();
                rlTranslatef(0.0f, 0.0f, side * 0.05f);
                drawBox({0.16f, 0.008f, 0.006f}, Color{200, 200, 196, 255}, light);
                rlPopMatrix();
            }
            rlPopMatrix();
            break;
        case Item::SteelBall: drawSphere(0.3f, color, ColorBrightness(color, 0.15f), light); break;
        case Item::Cone:
            drawRound(0.26f, 0.05f, 0.35f, color, color, 0.0f, light, 10);
            rlPushMatrix();  // a white band around the middle
            rlTranslatef(0, 0.05f, 0);
            drawRound(0.162f, 0.125f, 0.06f, Color{245, 245, 245, 255}, Color{245, 245, 245, 255}, 0.0f, light, 10);
            rlPopMatrix();
            break;
        case Item::Tyre: {
            // The round shape is drawn along y, so turn it to lie along x; the light turns with it.
            rlRotatef(90, 0, 0, 1);
            const Vector3 turned{light.y, -light.x, light.z};
            drawRound(0.38f, 0.38f, 0.13f, color, Color{60, 60, 66, 255}, 0.15f, turned, 14);
            drawRound(0.16f, 0.16f, 0.135f, Color{180, 182, 190, 255}, Color{180, 182, 190, 255}, 0.0f, turned, 10);  // hub
            break;
        }
        case Item::Count: break;
    }
}

Things::Things(b3WorldId physics) : physics_(physics) {
    barrel_ = b3CreateCylinder(0.9f, 0.3f, -0.45f, 12);
    std::vector<b3Vec3> points;
    for (int i = 0; i < 10; ++i) {  // a traffic cone, centred: wide at the bottom, narrow on top
        const float a = 2 * PI * i / 10;
        points.push_back({0.26f * std::cos(a), -0.35f, 0.26f * std::sin(a)});
        points.push_back({0.05f * std::cos(a), 0.35f, 0.05f * std::sin(a)});
    }
    cone_ = hullFrom(points);
    points.clear();
    for (int i = 0; i < 14; ++i) {  // a tyre: a short cylinder lying along x
        const float a = 2 * PI * i / 14;
        points.push_back({-0.13f, 0.38f * std::cos(a), 0.38f * std::sin(a)});
        points.push_back({0.13f, 0.38f * std::cos(a), 0.38f * std::sin(a)});
    }
    tyre_ = hullFrom(points);
}

Things::~Things() {
    clear();
    b3DestroyHull(barrel_);
    b3DestroyHull(cone_);
    b3DestroyHull(tyre_);
}

void Things::clear() {
    for (const Thing& t : things_) b3DestroyBody(t.body);
    things_.clear();
    blasts.clear();
}

void Things::spawn(Item item, Vector3 at, Vector3 velocity, float yaw) {
    static const Color kBallColors[] = {{230, 82, 70, 255}, {70, 140, 230, 255}, {245, 196, 60, 255}, {120, 200, 90, 255}};
    b3BodyDef def = b3DefaultBodyDef();
    def.type = b3_dynamicBody;
    def.position = {at.x, at.y, at.z};
    // Turned to face the player: a rotation of yaw around the y axis. Written out instead of
    // b3MakeQuatFromAxisAngle, whose debug-only check needs a symbol the bundled release build of
    // Box3D does not have, so debug builds would not link.
    def.rotation = b3Quat{{0.0f, std::sin(yaw / 2), 0.0f}, std::cos(yaw / 2)};
    def.linearVelocity = {velocity.x, velocity.y, velocity.z};
    def.angularVelocity = {velocity.z * 0.3f, 0.0f, -velocity.x * 0.3f};
    b3BodyId body = b3CreateBody(physics_, &def);

    b3ShapeDef shape = b3DefaultShapeDef();
    shape.filter.categoryBits = kThingBit;
    shape.filter.maskBits = kTerrainBit | kThingBit | kPlayerBit;
    shape.baseMaterial.friction = 0.6f;
    Thing thing{body, item, WHITE, 0.0f, 0.0f, 0.0f, false, Air, -1.0f};
    auto sphere = [&](float radius, float density, float bounce, float rolling) {
        const b3Sphere s{{0, 0, 0}, radius};
        shape.density = density;
        shape.baseMaterial.restitution = bounce;
        shape.baseMaterial.rollingResistance = rolling;
        b3CreateSphereShape(body, &shape, &s);
        thing.volume = 4.19f * radius * radius * radius;
        thing.bottom = radius;
    };
    auto box = [&](Vector3 half, float density, float bottom) {
        const b3BoxHull hull = b3MakeBoxHull(half.x, half.y, half.z);
        shape.density = density;
        b3CreateHullShape(body, &shape, &hull.base);
        thing.volume = 8 * half.x * half.y * half.z;
        thing.bottom = bottom;
    };
    // Densities are relative to water (1): below 1 floats, above sinks.
    switch (item) {
        case Item::Ball:
            sphere(0.25f, 2.0f, 0.55f, 0.05f);
            thing.color = kBallColors[serial_ % 4];
            break;
        case Item::Crate:
            box({0.35f, 0.35f, 0.35f}, 0.6f, 0.35f);
            thing.color = {176, 128, 72, 255};
            break;
        case Item::Barrel:
            shape.density = 0.5f;
            b3CreateHullShape(body, &shape, barrel_);
            thing.color = {190, 60, 50, 255};
            thing.volume = 3.14f * 0.3f * 0.3f * 0.9f;
            thing.bottom = 0.4f;
            break;
        case Item::BeachBall:
            sphere(0.5f, 0.12f, 0.8f, 0.02f);
            b3Body_SetLinearDamping(body, 0.3f);  // light things feel the air
            thing.color = {245, 245, 240, 255};
            break;
        case Item::Plank:
            box({0.9f, 0.07f, 0.22f}, 0.7f, 0.12f);
            thing.color = {201, 160, 104, 255};
            break;
        case Item::Bomb:
            sphere(0.22f, 3.0f, 0.3f, 0.1f);
            thing.color = {48, 50, 58, 255};
            thing.fuse = 2.5f;
            break;
        case Item::Domino:
            box({0.125f, 0.25f, 0.03125f}, 0.8f, 0.15f);
            thing.color = {30, 30, 36, 255};
            break;
        case Item::SteelBall:
            sphere(0.3f, 7.8f, 0.1f, 0.01f);
            thing.color = {170, 176, 186, 255};
            break;
        case Item::Cone:
            shape.density = 0.5f;
            b3CreateHullShape(body, &shape, cone_);
            thing.color = {245, 120, 30, 255};
            thing.volume = 0.06f;
            thing.bottom = 0.3f;
            break;
        case Item::Tyre:
            shape.density = 0.9f;
            shape.baseMaterial.friction = 0.9f;
            shape.baseMaterial.restitution = 0.5f;
            b3CreateHullShape(body, &shape, tyre_);
            thing.color = {38, 38, 42, 255};
            thing.volume = 3.14f * 0.38f * 0.38f * 0.26f;
            thing.bottom = 0.32f;
            break;
        case Item::Count: break;
    }
    ++serial_;
    things_.push_back(thing);
    // Keep the phone fast: forget the oldest thing (debris does not count).
    int total = 0;
    for (const Thing& t : things_) total += t.life < 0;
    if (total > kMaxThings) {
        auto oldest = std::find_if(things_.begin(), things_.end(), [](const Thing& t) { return t.life < 0; });
        b3DestroyBody(oldest->body);
        things_.erase(oldest);
    }
}

void Things::debris(Vector3 centre, uint8_t block, int count) {
    for (int i = 0; i < count; ++i) {
        int bits = 0;
        for (const Thing& t : things_) bits += t.life >= 0;
        if (bits >= kMaxDebris) {
            auto oldest = std::find_if(things_.begin(), things_.end(), [](const Thing& t) { return t.life >= 0; });
            b3DestroyBody(oldest->body);
            things_.erase(oldest);
        }
        const float rx = GetRandomValue(-100, 100) / 100.0f, rz = GetRandomValue(-100, 100) / 100.0f;
        b3BodyDef def = b3DefaultBodyDef();
        def.type = b3_dynamicBody;
        def.position = {centre.x + rx * 0.3f, centre.y + GetRandomValue(-30, 30) / 100.0f, centre.z + rz * 0.3f};
        def.linearVelocity = {rx * 2.5f, 2.5f + GetRandomValue(0, 150) / 100.0f, rz * 2.5f};
        def.angularVelocity = {rz * 6, rx * 4, -rx * 6};
        b3BodyId body = b3CreateBody(physics_, &def);
        b3ShapeDef shape = b3DefaultShapeDef();
        shape.filter.categoryBits = kDebrisBit;
        shape.filter.maskBits = kTerrainBit;  // only the ground: cheap, and they never get in the way
        shape.density = 1.5f;
        shape.baseMaterial.friction = 0.8f;
        const b3BoxHull hull = b3MakeCubeHull(0.09f);
        b3CreateHullShape(body, &shape, &hull.base);
        things_.push_back({body, Item::Count, blockColor(block), 0.0f, 0.09f, 0.0f, false, block,
                           1.4f + GetRandomValue(0, 100) / 100.0f});
    }
}

void Things::update(World& world, float dt) {
    std::vector<Vector3> explosions;
    for (size_t i = 0; i < things_.size();) {
        Thing& t = things_[i];
        const b3Vec3 bp = b3Body_GetPosition(t.body);
        const Vector3 p{bp.x, bp.y, bp.z};
        bool gone = p.y < -10.0f;
        if (t.life >= 0 && (t.life -= dt) <= 0) gone = true;

        // No collider under it (far from the player, or still being made): wait instead of falling.
        const bool ground = world.hasCollider(cell(p.x), cell(p.z));
        const bool enabled = b3Body_IsEnabled(t.body);
        if (!ground && enabled) b3Body_Disable(t.body);
        else if (ground && !enabled) b3Body_Enable(t.body);

        if (ground && t.life < 0) {
            // Floating: push up by the weight of the water the thing displaces.
            float surface = waterSurface(world, {p.x, p.y - t.bottom, p.z});
            if (surface < 0) surface = waterSurface(world, p);
            const float depth = surface < 0 ? 0.0f : Clamp((surface - (p.y - t.bottom)) / (2 * t.bottom), 0.0f, 1.0f);
            if ((depth > 0.0f) != t.wet) {
                t.wet = depth > 0.0f;
                b3Body_SetLinearDamping(t.body, t.wet ? 2.5f : (t.item == Item::BeachBall ? 0.3f : 0.0f));
                b3Body_SetAngularDamping(t.body, t.wet ? 2.0f : 0.0f);
            }
            // Water has density 1 here, like the shapes' densities (a crate at 0.6 floats, a ball at 2 sinks).
            if (t.wet) b3Body_ApplyForceToCenter(t.body, {0, t.volume * depth * 10.0f, 0}, true);
            if (t.item == Item::Bomb && (t.fuse -= dt) <= 0.0f) {
                explosions.push_back(p);
                gone = true;
            }
        }
        if (gone) {
            b3DestroyBody(t.body);
            things_.erase(things_.begin() + static_cast<long>(i));
        } else {
            ++i;
        }
    }

    for (const Vector3& at : explosions) {
        b3ExplosionDef blast = b3DefaultExplosionDef();
        blast.position = {at.x, at.y, at.z};
        blast.radius = 2.5f;
        blast.falloff = 2.5f;
        blast.impulsePerArea = 3.5f;
        blast.maskBits = kThingBit | kDebrisBit;
        b3World_Explode(physics_, &blast);
        blasts.push_back({at, 0.0f});
        // A crater: every breakable block inside the sphere goes, and some leave bits behind.
        constexpr float kRadius = 2.2f;
        int broken = 0;
        for (int y = cell(at.y - kRadius); y <= cell(at.y + kRadius); ++y) {
            for (int z = cell(at.z - kRadius); z <= cell(at.z + kRadius); ++z) {
                for (int x = cell(at.x - kRadius); x <= cell(at.x + kRadius); ++x) {
                    const Vector3 c{x + 0.5f, y + 0.5f, z + 0.5f};
                    if (Vector3DistanceSqr(c, at) > kRadius * kRadius) continue;
                    const uint8_t b = world.get(x, y, z);
                    if (b == Air || b == Water || !blockInfo(b).breakable || !world.loaded(x, z)) continue;
                    world.set(x, y, z, Air);
                    if (broken++ % 4 == 0) debris(c, b, 1);
                }
            }
        }
        // Bombs nearby go off too, a moment later.
        for (Thing& t : things_) {
            const b3Vec3 q = b3Body_GetPosition(t.body);
            if (t.item == Item::Bomb && Vector3Distance({q.x, q.y, q.z}, at) < 3.0f) t.fuse = std::min(t.fuse, 0.15f);
        }
    }
}

bool Things::push(Vector3 origin, Vector3 direction, float reach) {
    b3QueryFilter filter = b3DefaultQueryFilter();
    filter.categoryBits = kPlayerBit;
    filter.maskBits = kThingBit | kDebrisBit;
    const Vector3 d = Vector3Scale(direction, reach);
    const b3RayResult hit = b3World_CastRayClosest(physics_, {origin.x, origin.y, origin.z}, {d.x, d.y, d.z}, filter);
    if (!hit.hit) return false;
    const b3BodyId body = b3Shape_GetBody(hit.shapeId);
    const float mass = b3Body_GetMass(body);
    const float speed = std::min(6.0f, 30.0f / std::max(mass, 1.0f) + 2.0f);  // light things fly further
    b3Body_ApplyLinearImpulse(body, {direction.x * speed * mass, (direction.y + 0.4f) * speed * mass, direction.z * speed * mass},
                              hit.point, true);
    return true;
}

void Things::draw(const World& world, float time) const {
    for (const Thing& t : things_) {
        if (t.life >= 0) continue;
        const b3Vec3 p = b3Body_GetPosition(t.body);
        drawShadow(world, {p.x, p.y, p.z}, t.item == Item::Plank ? 0.6f : std::max(t.bottom, 0.25f));
    }
    for (const Thing& t : things_) {
        const b3Vec3 p = b3Body_GetPosition(t.body);
        const b3Quat q = b3Body_GetRotation(t.body);
        float angle = 0.0f;
        const b3Vec3 axis = b3GetAxisAngle(&angle, q);
        const b3Vec3 sun = b3InvRotateVector(q, {kSun.x, kSun.y, kSun.z});  // the light, seen from the body
        const Vector3 light{sun.x, sun.y, sun.z};
        rlPushMatrix();
        rlTranslatef(p.x, p.y, p.z);
        rlRotatef(angle * RAD2DEG, axis.x, axis.y, axis.z);
        if (t.life >= 0) {
            // Debris shrinks away at the end of its life.
            const float k = std::min(1.0f, t.life * 2.0f);
            drawBlockCube(world, t.block, 0.09f * k, light);
            rlPopMatrix();
            continue;
        }
        drawItem(t.item, t.color, t.fuse, time, light);
        rlPopMatrix();
    }
}
