// Voxel sandbox: walk around a block world made from noise and drop physical things from the
// hotbar. raylib draws, Box3D simulates the player, the things you drop and the terrain.
//
// Touch: the pad on the left walks, drag anywhere else to look around, the round buttons jump and
// throw, and a tap on the world throws too. Tap a hotbar slot to pick what to throw.
// Keyboard and mouse (remote access, or a keyboard on the phone): WASD walk, Space jumps, drag with
// the mouse or use the arrow keys to look, F or right click throws, 1-6 or the wheel pick an item,
// C clears everything you dropped.
#include "raylib.h"
#include "raymath.h"
#include "rlgl.h"
#include <box3d/box3d.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------------------------
// World: a fixed-size grid of blocks, cut into 16 x 16 columns ("chunks") for drawing.

constexpr int kChunk = 16;
constexpr int kChunksX = 8, kChunksZ = 8;
constexpr int kSizeX = kChunk * kChunksX, kSizeZ = kChunk * kChunksZ, kSizeY = 48;
constexpr int kSeaLevel = 13;                       // columns lower than this are under water
constexpr float kWaterTop = kSeaLevel - 0.15f;      // height of the water surface
constexpr uint32_t kSeed = 20261125;                // change it for another world

enum Block : uint8_t { Air, Grass, Dirt, Stone, Sand, Wood, Leaves, Snow };

static std::vector<uint8_t> gBlocks(static_cast<size_t>(kSizeX) * kSizeY * kSizeZ, Air);

static bool inside(int x, int y, int z) {
    return x >= 0 && x < kSizeX && y >= 0 && y < kSizeY && z >= 0 && z < kSizeZ;
}
static uint8_t blockAt(int x, int y, int z) {
    if (y < 0) return Stone;  // under the world counts as solid: no faces are made there
    if (!inside(x, y, z)) return Air;
    return gBlocks[(static_cast<size_t>(y) * kSizeZ + z) * kSizeX + x];
}
static void setBlock(int x, int y, int z, uint8_t b) {
    if (inside(x, y, z)) gBlocks[(static_cast<size_t>(y) * kSizeZ + z) * kSizeX + x] = b;
}
static bool solid(int x, int y, int z) { return blockAt(x, y, z) != Air; }
static int gTop[kSizeX][kSizeZ];  // height of the highest block of each column, trees included

// True over water: columns whose ground is below the water surface.
static bool waterAt(float x, float z) {
    const int ix = static_cast<int>(std::floor(x)), iz = static_cast<int>(std::floor(z));
    return ix >= 0 && ix < kSizeX && iz >= 0 && iz < kSizeZ && gTop[ix][iz] + 1 < kWaterTop;
}
static bool solidAt(Vector3 p) {
    return solid(static_cast<int>(std::floor(p.x)), static_cast<int>(std::floor(p.y)), static_cast<int>(std::floor(p.z)));
}

// Small integer hash: the same inputs always give the same "random" number.
static uint32_t hash3(int x, int y, int z) {
    uint32_t h = kSeed ^ (static_cast<uint32_t>(x) * 374761393u) ^ (static_cast<uint32_t>(y) * 2246822519u) ^
                 (static_cast<uint32_t>(z) * 668265263u);
    h = (h ^ (h >> 13)) * 1274126177u;
    return h ^ (h >> 16);
}
static float random01(int x, int y, int z) { return (hash3(x, y, z) & 0xFFFFFF) / 16777215.0f; }

// Value noise: random heights on a grid, smoothly blended in between, added up at several scales.
static float valueNoise(float x, float z, int layer) {
    const int x0 = static_cast<int>(std::floor(x)), z0 = static_cast<int>(std::floor(z));
    const float fx = x - x0, fz = z - z0;
    const float u = fx * fx * (3 - 2 * fx), v = fz * fz * (3 - 2 * fz);
    const float a = random01(x0, layer, z0), b = random01(x0 + 1, layer, z0);
    const float c = random01(x0, layer, z0 + 1), d = random01(x0 + 1, layer, z0 + 1);
    return Lerp(Lerp(a, b, u), Lerp(c, d, u), v);
}
static float fractalNoise(float x, float z, int layer) {
    float sum = 0, amplitude = 0.5f, total = 0;
    for (int octave = 0; octave < 4; ++octave) {
        sum += valueNoise(x, z, layer + octave * 17) * amplitude;
        total += amplitude;
        x *= 2.03f;
        z *= 2.03f;
        amplitude *= 0.5f;
    }
    return sum / total;
}

static void placeTree(int x, int ground, int z) {
    const int height = 4 + static_cast<int>(hash3(x, 7, z) % 2);
    for (int y = 1; y <= height; ++y) setBlock(x, ground + y, z, Wood);
    const int top = ground + height;
    for (int dy = -2; dy <= 1; ++dy) {
        const int radius = dy < 0 ? 2 : 1;
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                const bool corner = std::abs(dx) == radius && std::abs(dz) == radius;
                if (corner && (dy == 1 || hash3(x + dx, top + dy, z + dz) % 2)) continue;
                if (!solid(x + dx, top + dy, z + dz)) setBlock(x + dx, top + dy, z + dz, Leaves);
            }
        }
    }
}

static void generateWorld() {
    for (int x = 0; x < kSizeX; ++x) {
        for (int z = 0; z < kSizeZ; ++z) {
            const float hills = fractalNoise(x * 0.018f, z * 0.018f, 1);
            const float detail = fractalNoise(x * 0.07f, z * 0.07f, 9);
            // Low hills become lakes, high ones snowy peaks.
            const float t = Clamp((hills - 0.4f) / 0.45f, 0.0f, 1.0f);
            int height = static_cast<int>(5 + std::pow(t, 1.5f) * 32 + detail * 4);
            height = std::clamp(height, 2, kSizeY - 10);
            for (int y = 0; y <= height; ++y) {
                uint8_t b = Stone;
                if (y == height) b = height <= kSeaLevel ? Sand : height >= 30 ? Snow : Grass;
                else if (y >= height - 3) b = height <= kSeaLevel + 1 ? Sand : Dirt;
                setBlock(x, y, z, b);
            }
        }
    }
    // Trees: at most one per 6 x 6 cell, at a random spot of the cell, on grass only.
    for (int cx = 0; cx < kSizeX / 6; ++cx) {
        for (int cz = 0; cz < kSizeZ / 6; ++cz) {
            if (hash3(cx, 3, cz) % 100 > 30) continue;
            const int x = cx * 6 + 2 + static_cast<int>(hash3(cx, 4, cz) % 3);
            const int z = cz * 6 + 2 + static_cast<int>(hash3(cx, 5, cz) % 3);
            int ground = kSizeY - 1;
            while (ground > 0 && !solid(x, ground, z)) --ground;
            if (blockAt(x, ground, z) == Grass && ground + 8 < kSizeY) placeTree(x, ground, z);
        }
    }
    for (int x = 0; x < kSizeX; ++x) {
        for (int z = 0; z < kSizeZ; ++z) {
            int y = kSizeY - 1;
            while (y > 0 && !solid(x, y, z)) --y;
            gTop[x][z] = y;
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Meshes: one triangle list per chunk with only the faces that touch air, coloured per vertex
// with a fixed light per face direction and ambient occlusion in the corners.

static Color faceColor(uint8_t block, int axis, int sign) {
    const bool top = axis == 1 && sign > 0, bottom = axis == 1 && sign < 0;
    switch (block) {
        case Grass: return top ? Color{104, 171, 68, 255} : bottom ? Color{134, 96, 67, 255} : Color{112, 132, 64, 255};
        case Dirt: return {134, 96, 67, 255};
        case Stone: return {125, 128, 135, 255};
        case Sand: return {219, 204, 146, 255};
        case Wood: return axis == 1 ? Color{168, 134, 86, 255} : Color{105, 76, 48, 255};
        case Leaves: return {62, 128, 64, 255};
        case Snow: return top ? Color{242, 246, 250, 255} : Color{196, 200, 206, 255};
        default: return MAGENTA;
    }
}

struct MeshBuilder {
    std::vector<float> positions;
    std::vector<unsigned char> colors;
    std::vector<int32_t> collisionIndices;  // also kept for the physics mesh

    void vertex(Vector3 p, Color c) {
        positions.insert(positions.end(), {p.x, p.y, p.z});
        colors.insert(colors.end(), {c.r, c.g, c.b, c.a});
    }

    // Uploads to the GPU and frees the CPU copy; the mesh keeps only its buffers.
    Mesh upload() const {
        Mesh mesh{};
        mesh.vertexCount = static_cast<int>(positions.size() / 3);
        mesh.triangleCount = mesh.vertexCount / 3;
        if (mesh.vertexCount == 0) return mesh;
        mesh.vertices = static_cast<float*>(MemAlloc(positions.size() * sizeof(float)));
        mesh.colors = static_cast<unsigned char*>(MemAlloc(colors.size()));
        mesh.texcoords = static_cast<float*>(MemAlloc(mesh.vertexCount * 2 * sizeof(float)));  // zeroed
        std::memcpy(mesh.vertices, positions.data(), positions.size() * sizeof(float));
        std::memcpy(mesh.colors, colors.data(), colors.size());
        UploadMesh(&mesh, false);
        MemFree(mesh.vertices);
        MemFree(mesh.colors);
        MemFree(mesh.texcoords);
        mesh.vertices = nullptr;
        mesh.colors = nullptr;
        mesh.texcoords = nullptr;
        return mesh;
    }
};

static Color shade(Color c, float k, float jitter) {
    k *= jitter;
    return {static_cast<unsigned char>(std::min(255.0f, c.r * k)), static_cast<unsigned char>(std::min(255.0f, c.g * k)),
            static_cast<unsigned char>(std::min(255.0f, c.b * k)), c.a};
}

// Adds the face of block (x, y, z) that looks along +axis (sign 1) or -axis (sign -1).
// The face's corners run counter-clockwise seen from outside, as raylib and Box3D expect.
static void addFace(MeshBuilder& out, std::vector<b3Vec3>* physics, int x, int y, int z, int axis, int sign) {
    static const float kFaceLight[3][2] = {{0.72f, 0.82f}, {0.5f, 1.0f}, {0.62f, 0.9f}};  // [axis][sign < 0, > 0]
    static const float kOcclusion[4] = {0.5f, 0.68f, 0.84f, 1.0f};
    const int u = (axis + 1) % 3, v = (axis + 2) % 3;
    const int base[3] = {x, y, z};
    int normal[3] = {0, 0, 0};
    normal[axis] = sign;

    const uint8_t block = blockAt(x, y, z);
    const Color color = faceColor(block, axis, sign);
    const float light = kFaceLight[axis][sign > 0 ? 1 : 0];
    const float jitter = 0.94f + 0.12f * random01(x, y, z);

    Vector3 corners[4];
    float occlusion[4];
    static const int kCornerUV[4][2] = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    for (int i = 0; i < 4; ++i) {
        float p[3] = {static_cast<float>(x), static_cast<float>(y), static_cast<float>(z)};
        p[axis] += sign > 0 ? 1.0f : 0.0f;
        p[u] += kCornerUV[i][0];
        p[v] += kCornerUV[i][1];
        corners[i] = {p[0], p[1], p[2]};
        // Ambient occlusion: look at the three blocks touching this corner in front of the face.
        const int su = kCornerUV[i][0] ? 1 : -1, sv = kCornerUV[i][1] ? 1 : -1;
        int a[3], b[3], c[3];
        for (int k = 0; k < 3; ++k) a[k] = b[k] = c[k] = base[k] + normal[k];
        a[u] += su;
        b[v] += sv;
        c[u] += su;
        c[v] += sv;
        const int side1 = solid(a[0], a[1], a[2]), side2 = solid(b[0], b[1], b[2]), corner = solid(c[0], c[1], c[2]);
        occlusion[i] = kOcclusion[side1 && side2 ? 0 : 3 - side1 - side2 - corner];
    }
    int order[4] = {0, 1, 2, 3};
    if (sign < 0) std::swap(order[1], order[3]);  // the back side winds the other way
    // Split the quad along the diagonal with the lighter corners, so the shading stays smooth.
    if (occlusion[order[0]] + occlusion[order[2]] < occlusion[order[1]] + occlusion[order[3]]) {
        const int first = order[0];
        order[0] = order[1];
        order[1] = order[2];
        order[2] = order[3];
        order[3] = first;
    }
    static const int kTriangles[6] = {0, 1, 2, 0, 2, 3};
    for (int t : kTriangles) {
        const int i = order[t];
        out.vertex(corners[i], shade(color, light * occlusion[i], jitter));
    }
    if (physics != nullptr) {
        for (int t : kTriangles) {
            const Vector3 p = corners[order[t]];
            physics->push_back({p.x, p.y, p.z});
        }
    }
}

struct Chunk {
    Mesh land{};
    Mesh water{};
};

// Builds the chunk's land and water meshes and appends its faces to the collision triangles.
static Chunk buildChunk(int cx, int cz, std::vector<b3Vec3>& collision) {
    MeshBuilder land, water;
    for (int x = cx * kChunk; x < (cx + 1) * kChunk; ++x) {
        for (int z = cz * kChunk; z < (cz + 1) * kChunk; ++z) {
            for (int y = 0; y <= gTop[x][z]; ++y) {
                if (!solid(x, y, z)) continue;
                for (int axis = 0; axis < 3; ++axis) {
                    for (int sign = -1; sign <= 1; sign += 2) {
                        int n[3] = {x, y, z};
                        n[axis] += sign;
                        if (!solid(n[0], n[1], n[2])) addFace(land, &collision, x, y, z, axis, sign);
                    }
                }
            }
            if (waterAt(x + 0.5f, z + 0.5f)) {
                const float fx = static_cast<float>(x), fz = static_cast<float>(z);
                const Color c{58, 124, 196, 165};
                const Vector3 a{fx, kWaterTop, fz}, b{fx, kWaterTop, fz + 1}, d{fx + 1, kWaterTop, fz + 1}, e{fx + 1, kWaterTop, fz};
                for (Vector3 p : {a, b, d, a, d, e}) water.vertex(p, c);
            }
        }
    }
    return {land.upload(), water.upload()};
}

// Flat clouds high up, made from a coarser noise; drawn twice so they can scroll forever.
constexpr float kCloudCell = 8.0f, kCloudHeight = 64.0f;
constexpr int kCloudCells = 40;
static Mesh buildClouds() {
    MeshBuilder out;
    auto cloud = [](int x, int z) {
        x = (x + kCloudCells) % kCloudCells;
        z = (z + kCloudCells) % kCloudCells;
        return fractalNoise(x * 0.35f, z * 0.35f, 40) > 0.56f;
    };
    const Color top{250, 251, 255, 235}, side{226, 230, 240, 235}, bottom{205, 210, 224, 235};
    for (int x = 0; x < kCloudCells; ++x) {
        for (int z = 0; z < kCloudCells; ++z) {
            if (!cloud(x, z)) continue;
            const float x0 = x * kCloudCell, z0 = z * kCloudCell, x1 = x0 + kCloudCell, z1 = z0 + kCloudCell;
            const float y0 = kCloudHeight, y1 = kCloudHeight + 2.5f;
            auto quad = [&](Vector3 a, Vector3 b, Vector3 c, Vector3 d, Color col) {
                for (Vector3 p : {a, b, c, a, c, d}) out.vertex(p, col);
            };
            quad({x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, top);
            quad({x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, bottom);
            if (!cloud(x - 1, z)) quad({x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}, side);
            if (!cloud(x + 1, z)) quad({x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}, side);
            if (!cloud(x, z - 1)) quad({x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}, side);
            if (!cloud(x, z + 1)) quad({x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}, side);
        }
    }
    return out.upload();
}

// Vertex colours plus distance fog, so the edge of the world fades into the sky.
static const char* kVoxelVertex = R"(
ATTRIBUTE vec3 vertexPosition;
ATTRIBUTE vec4 vertexColor;
uniform mat4 mvp;
uniform mat4 matModel;
uniform vec3 viewPos;
VARYING_OUT vec4 color;
VARYING_OUT float fogDistance;
void main() {
    vec4 world = matModel * vec4(vertexPosition, 1.0);
    color = vertexColor;
    fogDistance = length(world.xyz - viewPos);
    gl_Position = mvp * vec4(vertexPosition, 1.0);
}
)";
static const char* kVoxelFragment = R"(
precision mediump float;
VARYING_IN vec4 color;
VARYING_IN float fogDistance;
uniform vec4 colDiffuse;
uniform vec3 fogColor;
uniform vec2 fogRange;
void main() {
    float fog = clamp((fogDistance - fogRange.x) / (fogRange.y - fogRange.x), 0.0, 1.0);
    FRAGMENT = vec4(mix(color.rgb * colDiffuse.rgb, fogColor, fog), color.a * colDiffuse.a);
}
)";

static Shader loadVoxelShader() {
#if defined(__ANDROID__)
    // OpenGL ES 2 (GLSL ES 1.00), what raylib uses on phones.
    const std::string vertexHeader = "#version 100\n#define ATTRIBUTE attribute\n#define VARYING_OUT varying\n";
    const std::string fragmentHeader = "#version 100\n#define VARYING_IN varying\n#define FRAGMENT gl_FragColor\n";
#else
    // Desktop OpenGL 3.3, so the same file also runs in a raylib build on a computer.
    const std::string vertexHeader = "#version 330\n#define ATTRIBUTE in\n#define VARYING_OUT out\n";
    const std::string fragmentHeader =
        "#version 330\n#define VARYING_IN in\nout vec4 fragmentColor;\n#define FRAGMENT fragmentColor\n";
#endif
    const std::string vertex = vertexHeader + kVoxelVertex, fragment = fragmentHeader + kVoxelFragment;
    return LoadShaderFromMemory(vertex.c_str(), fragment.c_str());
}

// ---------------------------------------------------------------------------------------------
// Things to throw: each one is a Box3D body drawn with a few lit triangles.

enum class Item { Ball, Crate, Barrel, BeachBall, Plank, Bomb };
constexpr int kItemCount = 6;
static const char* const kItemNames[kItemCount] = {"Ball", "Crate", "Barrel", "Beach ball", "Plank", "Bomb"};

constexpr uint64_t kTerrainBit = 1, kThingBit = 2, kPlayerBit = 4;
constexpr int kMaxThings = 140;

struct Thing {
    b3BodyId body;
    Item item;
    Color color;
    float volume;     // m^3, for floating in water
    float bottom;     // distance from the centre to the lowest point, roughly
    float fuse;       // seconds left before a bomb goes off
    bool wet;
};

static b3HullData* gBarrelHull = nullptr;

static Thing makeThing(b3WorldId world, Item item, b3Vec3 position, b3Vec3 velocity, float yaw, int serial) {
    static const Color kBallColors[] = {{230, 82, 70, 255}, {70, 140, 230, 255}, {245, 196, 60, 255}, {120, 200, 90, 255}};
    b3BodyDef def = b3DefaultBodyDef();
    def.type = b3_dynamicBody;
    def.position = position;
    // Turned to face the player (planks fly sideways): a rotation of yaw around the y axis. Written
    // out instead of b3MakeQuatFromAxisAngle, whose debug-only check needs a symbol the bundled
    // release build of Box3D does not have, so debug builds would not link.
    def.rotation = b3Quat{{0.0f, std::sin(yaw / 2), 0.0f}, std::cos(yaw / 2)};
    def.linearVelocity = velocity;
    def.angularVelocity = {velocity.z * 0.3f, 0.0f, -velocity.x * 0.3f};
    b3BodyId body = b3CreateBody(world, &def);

    b3ShapeDef shape = b3DefaultShapeDef();
    shape.filter.categoryBits = kThingBit;
    shape.baseMaterial.friction = 0.6f;
    Thing thing{body, item, WHITE, 0.0f, 0.0f, 0.0f, false};
    switch (item) {
        case Item::Ball: {
            const b3Sphere sphere{{0, 0, 0}, 0.25f};
            shape.density = 2.0f;  // sinks in water
            shape.baseMaterial.restitution = 0.55f;
            shape.baseMaterial.rollingResistance = 0.05f;
            b3CreateSphereShape(body, &shape, &sphere);
            thing.color = kBallColors[serial % 4];
            thing.volume = 4.19f * 0.25f * 0.25f * 0.25f;
            thing.bottom = 0.25f;
            break;
        }
        case Item::Crate: {
            const b3BoxHull hull = b3MakeCubeHull(0.35f);
            shape.density = 0.6f;
            b3CreateHullShape(body, &shape, &hull.base);
            thing.color = {176, 128, 72, 255};
            thing.volume = 0.7f * 0.7f * 0.7f;
            thing.bottom = 0.35f;
            break;
        }
        case Item::Barrel:
            shape.density = 0.5f;
            b3CreateHullShape(body, &shape, gBarrelHull);
            thing.color = {190, 60, 50, 255};
            thing.volume = 3.14f * 0.3f * 0.3f * 0.9f;
            thing.bottom = 0.45f;
            break;
        case Item::BeachBall: {
            const b3Sphere sphere{{0, 0, 0}, 0.5f};
            shape.density = 0.12f;
            shape.baseMaterial.restitution = 0.8f;
            shape.baseMaterial.rollingResistance = 0.02f;
            b3CreateSphereShape(body, &shape, &sphere);
            b3Body_SetLinearDamping(body, 0.3f);  // light things feel the air
            thing.color = {245, 245, 240, 255};
            thing.volume = 4.19f * 0.125f;
            thing.bottom = 0.5f;
            break;
        }
        case Item::Plank: {
            const b3BoxHull hull = b3MakeBoxHull(0.9f, 0.07f, 0.22f);
            shape.density = 0.7f;
            b3CreateHullShape(body, &shape, &hull.base);
            thing.color = {201, 160, 104, 255};
            thing.volume = 1.8f * 0.14f * 0.44f;
            thing.bottom = 0.2f;
            break;
        }
        case Item::Bomb: {
            const b3Sphere sphere{{0, 0, 0}, 0.22f};
            shape.density = 3.0f;
            shape.baseMaterial.restitution = 0.3f;
            shape.baseMaterial.rollingResistance = 0.1f;
            b3CreateSphereShape(body, &shape, &sphere);
            thing.color = {48, 50, 58, 255};
            thing.volume = 4.19f * 0.22f * 0.22f * 0.22f;
            thing.bottom = 0.22f;
            thing.fuse = 2.5f;
            break;
        }
    }
    return thing;
}

// Light for the things, from the same side as the brighter faces of the terrain.
static const Vector3 kSun = Vector3Normalize({0.45f, 1.0f, 0.3f});

static Color lit(Color c, Vector3 normal, Vector3 light) {
    const float k = 0.5f + 0.5f * std::max(0.0f, Vector3DotProduct(normal, light));
    return {static_cast<unsigned char>(c.r * k), static_cast<unsigned char>(c.g * k), static_cast<unsigned char>(c.b * k), c.a};
}

static void litVertex(Vector3 p, Vector3 n, Color c, Vector3 light) {
    const Color k = lit(c, n, light);
    rlColor4ub(k.r, k.g, k.b, k.a);
    rlVertex3f(p.x, p.y, p.z);
}

static void drawBox(Vector3 half, Color color, Vector3 light) {
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

// A sphere; with a second colour, alternate slices take it (a beach ball).
static void drawSphere(float radius, Color color, Color stripes, Vector3 light) {
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

static void drawCylinder(float radius, float halfHeight, Color color, Color bands, Vector3 light) {
    constexpr int kSides = 12;
    rlBegin(RL_TRIANGLES);
    for (int i = 0; i < kSides; ++i) {
        const float a0 = 2 * PI * i / kSides, a1 = 2 * PI * (i + 1) / kSides;
        const Vector3 n0{std::cos(a0), 0, std::sin(a0)}, n1{std::cos(a1), 0, std::sin(a1)};
        // Side in three rings: band, body, band.
        const float ys[4] = {-halfHeight, -halfHeight * 0.6f, halfHeight * 0.6f, halfHeight};
        for (int r = 0; r < 3; ++r) {
            const Color c = r == 1 ? color : bands;
            const Vector3 p00{n0.x * radius, ys[r], n0.z * radius}, p01{n0.x * radius, ys[r + 1], n0.z * radius};
            const Vector3 p10{n1.x * radius, ys[r], n1.z * radius}, p11{n1.x * radius, ys[r + 1], n1.z * radius};
            litVertex(p00, n0, c, light);
            litVertex(p01, n0, c, light);
            litVertex(p11, n1, c, light);
            litVertex(p00, n0, c, light);
            litVertex(p11, n1, c, light);
            litVertex(p10, n1, c, light);
        }
        for (int s = -1; s <= 1; s += 2) {  // caps
            const Vector3 up{0, static_cast<float>(s), 0}, centre{0, s * halfHeight, 0};
            const Vector3 p0{n0.x * radius, s * halfHeight, n0.z * radius}, p1{n1.x * radius, s * halfHeight, n1.z * radius};
            litVertex(centre, up, bands, light);
            litVertex(s > 0 ? p1 : p0, up, bands, light);
            litVertex(s > 0 ? p0 : p1, up, bands, light);
        }
    }
    rlEnd();
}

static void drawThing(const Thing& thing, float time) {
    const b3Vec3 p = b3Body_GetPosition(thing.body);
    const b3Quat q = b3Body_GetRotation(thing.body);
    float angle = 0.0f;
    const b3Vec3 axis = b3GetAxisAngle(&angle, q);
    const b3Vec3 sun = b3InvRotateVector(q, {kSun.x, kSun.y, kSun.z});  // the light, seen from the body
    const Vector3 light{sun.x, sun.y, sun.z};
    rlPushMatrix();
    rlTranslatef(p.x, p.y, p.z);
    rlRotatef(angle * RAD2DEG, axis.x, axis.y, axis.z);
    switch (thing.item) {
        case Item::Ball: drawSphere(0.25f, thing.color, ColorBrightness(thing.color, -0.12f), light); break;
        case Item::Crate:
            drawBox({0.35f, 0.35f, 0.35f}, thing.color, light);
            drawBox({0.36f, 0.06f, 0.36f}, ColorBrightness(thing.color, -0.35f), light);  // a strap around it
            break;
        case Item::Barrel: drawCylinder(0.3f, 0.45f, thing.color, Color{70, 70, 78, 255}, light); break;
        case Item::BeachBall: drawSphere(0.5f, thing.color, Color{236, 92, 72, 255}, light); break;
        case Item::Plank: drawBox({0.9f, 0.07f, 0.22f}, thing.color, light); break;
        case Item::Bomb: {
            // Blinks faster as the fuse burns down.
            const bool blink = std::fmod(time * (8.0f - 2.5f * thing.fuse), 1.0f) < 0.5f;
            drawSphere(0.22f, blink ? Color{200, 50, 40, 255} : thing.color, thing.color, light);
            rlPushMatrix();
            rlTranslatef(0, 0.24f, 0);
            drawBox({0.04f, 0.06f, 0.04f}, Color{200, 180, 120, 255}, light);
            rlPopMatrix();
            break;
        }
    }
    rlPopMatrix();
}

// A soft dark disc on the ground under a thing, so you can tell where it is in the air.
static void drawShadow(Vector3 p, float radius) {
    const int x = static_cast<int>(std::floor(p.x)), z = static_cast<int>(std::floor(p.z));
    int y = std::min(static_cast<int>(std::floor(p.y)), kSizeY - 1);
    while (y >= 0 && !solid(x, y, z)) --y;
    if (y < 0) return;
    const float ground = y + 1.02f, height = p.y - ground;
    if (height > 8.0f || height < -0.5f) return;
    const float r = radius * (1.1f - height * 0.08f);
    const unsigned char alpha = static_cast<unsigned char>(110 * (1.0f - height / 8.0f));
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

// ---------------------------------------------------------------------------------------------
// Screen layout. Sizes are in units of 1/360 of the short side, so it fits any phone.

struct Layout {
    float unit, margin;
    Vector2 pad;          // centre of the move pad
    float padRadius;
    Vector2 jump, toss;   // centres of the round buttons
    float jumpRadius, tossRadius;
    Rectangle slots[kItemCount];
    Rectangle clear;
    int textSize;

    Layout(float w, float h) {
        unit = std::min(w, h) / 360.0f;
        margin = 14 * unit;
        padRadius = 54 * unit;
        jumpRadius = 34 * unit;
        tossRadius = 28 * unit;
        textSize = std::max(10, static_cast<int>(14 * unit));
        const float slot = 42 * unit, gap = 4 * unit;
        const float barWidth = kItemCount * slot + (kItemCount - 1) * gap;
        const float sides = 2 * (margin + 2 * padRadius + 10 * unit);
        // Wide screens: everything on the bottom row. Narrow ones: the controls sit above the hotbar.
        const float barY = h - margin - slot;
        const float controlsBottom = w - sides >= barWidth ? h - margin : barY - 12 * unit;
        for (int i = 0; i < kItemCount; ++i) slots[i] = {(w - barWidth) / 2 + i * (slot + gap), barY, slot, slot};
        pad = {margin + padRadius + 6 * unit, controlsBottom - padRadius};
        jump = {w - margin - jumpRadius - 6 * unit, controlsBottom - jumpRadius - 4 * unit};
        toss = {jump.x - 58 * unit, jump.y - 66 * unit};
        clear = {w - margin - 64 * unit, margin, 64 * unit, 28 * unit};
    }
};

static void drawItemIcon(Item item, Vector2 c, float s) {
    switch (item) {
        case Item::Ball: DrawCircleV(c, s * 0.28f, Color{230, 82, 70, 255}); break;
        case Item::Crate:
            DrawRectangleRec({c.x - s * 0.3f, c.y - s * 0.3f, s * 0.6f, s * 0.6f}, Color{176, 128, 72, 255});
            DrawRectangleRec({c.x - s * 0.3f, c.y - s * 0.06f, s * 0.6f, s * 0.12f}, Color{112, 80, 44, 255});
            break;
        case Item::Barrel:
            DrawRectangleRounded({c.x - s * 0.22f, c.y - s * 0.32f, s * 0.44f, s * 0.64f}, 0.4f, 6, Color{190, 60, 50, 255});
            DrawRectangleRec({c.x - s * 0.22f, c.y - s * 0.2f, s * 0.44f, s * 0.07f}, Color{70, 70, 78, 255});
            DrawRectangleRec({c.x - s * 0.22f, c.y + s * 0.13f, s * 0.44f, s * 0.07f}, Color{70, 70, 78, 255});
            break;
        case Item::BeachBall:
            DrawCircleV(c, s * 0.34f, Color{245, 245, 240, 255});
            DrawCircleSector(c, s * 0.34f, 0, 60, 8, Color{236, 92, 72, 255});
            DrawCircleSector(c, s * 0.34f, 120, 180, 8, Color{236, 92, 72, 255});
            DrawCircleSector(c, s * 0.34f, 240, 300, 8, Color{236, 92, 72, 255});
            break;
        case Item::Plank:
            DrawRectanglePro({c.x, c.y, s * 0.7f, s * 0.14f}, {s * 0.35f, s * 0.07f}, -30, Color{201, 160, 104, 255});
            break;
        case Item::Bomb:
            DrawCircleV({c.x, c.y + s * 0.05f}, s * 0.25f, Color{48, 50, 58, 255});
            DrawLineEx({c.x + s * 0.1f, c.y - s * 0.15f}, {c.x + s * 0.22f, c.y - s * 0.32f}, s * 0.05f, Color{200, 180, 120, 255});
            DrawCircleV({c.x + s * 0.24f, c.y - s * 0.34f}, s * 0.05f, ORANGE);
            break;
    }
}

// ---------------------------------------------------------------------------------------------
// Fingers (or the mouse) and what each one is doing.

enum class Role { Look, Pad, Jump, Button };

struct Finger {
    int id;
    Role role;
    Vector2 start, last;
    float pressedAt;
    bool moved, alive;
};

struct Pointer {
    int id;
    Vector2 position;
};

static std::vector<Pointer> readPointers() {
    std::vector<Pointer> pointers;
    const int count = GetTouchPointCount();
    for (int i = 0; i < count; ++i) pointers.push_back({GetTouchPointId(i), GetTouchPosition(i)});
#if !defined(__ANDROID__)
    // On a computer the mouse is the only finger.
    if (count == 0 && IsMouseButtonDown(MOUSE_BUTTON_LEFT)) pointers.push_back({-1, GetMousePosition()});
#endif
    return pointers;
}

// ---------------------------------------------------------------------------------------------

int main() {
    InitWindow(0, 0, "@PROJECT_NAME@");
    SetTargetFPS(60);

    generateWorld();

    b3WorldDef worldDef = b3DefaultWorldDef();
    worldDef.gravity = {0.0f, -10.0f, 0.0f};
    b3WorldId world = b3CreateWorld(&worldDef);

    // Drawing meshes per chunk; one collision mesh for the whole terrain. Box3D keeps the
    // triangles in a tree, so a body only ever tests the few triangles next to it.
    std::vector<Chunk> chunks;
    std::vector<b3Vec3> triangles;
    for (int cx = 0; cx < kChunksX; ++cx)
        for (int cz = 0; cz < kChunksZ; ++cz) chunks.push_back(buildChunk(cx, cz, triangles));
    std::vector<int32_t> indices(triangles.size());
    for (size_t i = 0; i < indices.size(); ++i) indices[i] = static_cast<int32_t>(i);
    b3MeshDef meshDef{};
    meshDef.vertices = triangles.data();
    meshDef.indices = indices.data();
    meshDef.vertexCount = static_cast<int>(triangles.size());
    meshDef.triangleCount = static_cast<int>(triangles.size() / 3);
    meshDef.weldVertices = true;   // shared corners make one connected surface...
    meshDef.weldTolerance = 0.001f;
    meshDef.identifyEdges = true;  // ...so things slide over the seams between blocks without bumps
    meshDef.useMedianSplit = true;
    b3MeshData* terrainMesh = b3CreateMesh(&meshDef, nullptr, 0);
    std::vector<b3Vec3>().swap(triangles);
    std::vector<int32_t>().swap(indices);

    b3BodyDef terrainDef = b3DefaultBodyDef();  // static
    b3BodyId terrain = b3CreateBody(world, &terrainDef);
    b3ShapeDef terrainShape = b3DefaultShapeDef();
    terrainShape.filter.categoryBits = kTerrainBit;
    terrainShape.baseMaterial.friction = 0.7f;
    b3CreateMeshShape(terrain, &terrainShape, terrainMesh, {1.0f, 1.0f, 1.0f});

    gBarrelHull = b3CreateCylinder(0.9f, 0.3f, -0.45f, 12);

    // The player: an upright capsule that never tips over. We set its walking speed ourselves
    // and let Box3D handle gravity, collisions and pushing things around.
    constexpr float kRadius = 0.3f, kHalfLine = 0.6f, kEye = 0.7f;  // 1.8 m tall, eyes at 1.6 m
    // Start on the grass nearest to the middle of the map, away from trees.
    auto clearing = [](int x, int z) {
        if (blockAt(x, gTop[x][z], z) != Grass) return false;
        for (int dx = -5; dx <= 5; ++dx) {
            for (int dz = -5; dz <= 5; ++dz) {
                const uint8_t b = blockAt(x + dx, gTop[std::clamp(x + dx, 0, kSizeX - 1)][std::clamp(z + dz, 0, kSizeZ - 1)], z + dz);
                if (b == Wood || b == Leaves) return false;
            }
        }
        return true;
    };
    int spawnX = kSizeX / 2, spawnZ = kSizeZ / 2;
    for (int ring = 0; ring < 40; ++ring) {
        bool found = false;
        for (int dx = -ring; dx <= ring && !found; ++dx) {
            for (int dz = -ring; dz <= ring && !found; ++dz) {
                const int x = kSizeX / 2 + dx, z = kSizeZ / 2 + dz;
                if (clearing(x, z)) {
                    spawnX = x;
                    spawnZ = z;
                    found = true;
                }
            }
        }
        if (found) break;
    }
    const b3Vec3 spawn{spawnX + 0.5f, gTop[spawnX][spawnZ] + 1.0f + kRadius + kHalfLine + 0.05f, spawnZ + 0.5f};
    b3BodyDef playerDef = b3DefaultBodyDef();
    playerDef.type = b3_dynamicBody;
    playerDef.position = spawn;
    playerDef.motionLocks.angularX = playerDef.motionLocks.angularY = playerDef.motionLocks.angularZ = true;
    playerDef.enableSleep = false;
    playerDef.gravityScale = 2.0f;  // snappier jumps than real life
    b3BodyId player = b3CreateBody(world, &playerDef);
    b3ShapeDef playerShape = b3DefaultShapeDef();
    playerShape.filter.categoryBits = kPlayerBit;
    playerShape.density = 60.0f;  // heavy enough to shove crates around
    playerShape.baseMaterial.friction = 0.0f;  // slides along walls instead of sticking to them
    const b3Capsule capsule{{0, -kHalfLine, 0}, {0, kHalfLine, 0}, kRadius};
    b3CreateCapsuleShape(player, &playerShape, &capsule);

    Shader shader = loadVoxelShader();
    const int viewPosLoc = GetShaderLocation(shader, "viewPos");
    const Color sky{148, 200, 238, 255};
    const float fogColor[3] = {sky.r / 255.0f, sky.g / 255.0f, sky.b / 255.0f};
    const float fogRange[2] = {38.0f, 92.0f};
    SetShaderValue(shader, GetShaderLocation(shader, "fogColor"), fogColor, SHADER_UNIFORM_VEC3);
    SetShaderValue(shader, GetShaderLocation(shader, "fogRange"), fogRange, SHADER_UNIFORM_VEC2);
    Material material = LoadMaterialDefault();
    material.shader = shader;
    Mesh clouds = buildClouds();

    Camera3D camera{};
    camera.up = {0.0f, 1.0f, 0.0f};
    camera.fovy = 70.0f;
    camera.projection = CAMERA_PERSPECTIVE;
    float yaw = 0.4f, pitch = -0.15f;

    std::vector<Thing> things;
    std::vector<Finger> fingers;
    struct Blast { Vector3 at; float age; };
    std::vector<Blast> blasts;
    int selected = 0, serial = 0;
    bool grounded = false;
    float time = 0.0f;

    while (!WindowShouldClose()) {
        const float dt = std::min(GetFrameTime(), 1.0f / 20.0f);
        time += dt;
        const float w = static_cast<float>(GetScreenWidth()), h = static_cast<float>(GetScreenHeight());
        const Layout ui(w, h);
        const float lookSpeed = 0.006f / ui.unit;  // radians per pixel, the same feel on every screen

        // --- Input -----------------------------------------------------------------------------
        Vector2 move{0, 0};  // x: right, y: forward, length up to 1
        bool jumpHeld = false, toss = false;

        for (Finger& f : fingers) f.alive = false;
        for (const Pointer& p : readPointers()) {
            auto it = std::find_if(fingers.begin(), fingers.end(), [&](const Finger& f) { return f.id == p.id; });
            if (it == fingers.end()) {  // a new finger: what did it land on?
                Finger f{p.id, Role::Look, p.position, p.position, time, false, true};
                bool onSlot = false;
                for (int i = 0; i < kItemCount; ++i) {
                    if (CheckCollisionPointRec(p.position, ui.slots[i])) {
                        selected = i;
                        onSlot = true;
                    }
                }
                if (onSlot) f.role = Role::Button;
                else if (CheckCollisionPointRec(p.position, ui.clear)) {
                    for (const Thing& t : things) b3DestroyBody(t.body);
                    things.clear();
                    f.role = Role::Button;
                } else if (Vector2Distance(p.position, ui.jump) <= ui.jumpRadius * 1.2f) f.role = Role::Jump;
                else if (Vector2Distance(p.position, ui.toss) <= ui.tossRadius * 1.2f) {
                    toss = true;
                    f.role = Role::Button;
                } else if (Vector2Distance(p.position, ui.pad) <= ui.padRadius * 1.5f) f.role = Role::Pad;
                fingers.push_back(f);
                it = fingers.end() - 1;
            }
            Finger& f = *it;
            f.alive = true;
            if (Vector2Distance(p.position, f.start) > 10 * ui.unit) f.moved = true;
            if (f.role == Role::Look) {
                yaw -= (p.position.x - f.last.x) * lookSpeed;
                pitch = Clamp(pitch - (p.position.y - f.last.y) * lookSpeed, -1.5f, 1.5f);
            } else if (f.role == Role::Pad) {
                Vector2 offset = Vector2Scale(Vector2Subtract(p.position, ui.pad), 1.0f / ui.padRadius);
                if (Vector2Length(offset) > 1.0f) offset = Vector2Normalize(offset);
                move = {offset.x, -offset.y};
            } else if (f.role == Role::Jump) {
                jumpHeld = true;
            }
            f.last = p.position;
        }
        for (const Finger& f : fingers) {  // a quick tap on the world throws
            if (!f.alive && f.role == Role::Look && !f.moved && time - f.pressedAt < 0.3f) toss = true;
        }
        fingers.erase(std::remove_if(fingers.begin(), fingers.end(), [](const Finger& f) { return !f.alive; }), fingers.end());

        // Keyboard and mouse.
        if (IsKeyDown(KEY_W)) move.y += 1;
        if (IsKeyDown(KEY_S)) move.y -= 1;
        if (IsKeyDown(KEY_D)) move.x += 1;
        if (IsKeyDown(KEY_A)) move.x -= 1;
        if (Vector2Length(move) > 1.0f) move = Vector2Normalize(move);
        if (IsKeyDown(KEY_LEFT)) yaw += 2.2f * dt;
        if (IsKeyDown(KEY_RIGHT)) yaw -= 2.2f * dt;
        if (IsKeyDown(KEY_UP)) pitch = std::min(1.5f, pitch + 1.6f * dt);
        if (IsKeyDown(KEY_DOWN)) pitch = std::max(-1.5f, pitch - 1.6f * dt);
        if (IsKeyDown(KEY_SPACE)) jumpHeld = true;
        if (IsKeyPressed(KEY_F) || IsKeyPressed(KEY_ENTER) || IsMouseButtonPressed(MOUSE_BUTTON_RIGHT)) toss = true;
        for (int i = 0; i < kItemCount; ++i) {
            if (IsKeyPressed(KEY_ONE + i)) selected = i;
        }
        const float wheel = GetMouseWheelMove();
        if (wheel != 0) selected = (selected + (wheel < 0 ? 1 : kItemCount - 1)) % kItemCount;
        if (IsKeyPressed(KEY_C)) {
            for (const Thing& t : things) b3DestroyBody(t.body);
            things.clear();
        }

        // --- Player ----------------------------------------------------------------------------
        const Vector3 forward{std::sin(yaw), 0, std::cos(yaw)};
        const Vector3 right = Vector3CrossProduct(forward, {0, 1, 0});
        const b3Vec3 bp = b3Body_GetPosition(player);
        const Vector3 body{bp.x, bp.y, bp.z};
        const b3Vec3 velocity = b3Body_GetLinearVelocity(player);
        const bool swimming = body.y < kWaterTop && waterAt(body.x, body.z);

        // Standing on something? Cast a few short rays down from the capsule's base.
        grounded = false;
        b3QueryFilter feetFilter = b3DefaultQueryFilter();
        feetFilter.categoryBits = kPlayerBit;
        feetFilter.maskBits = kTerrainBit | kThingBit;
        const float offsets[5][2] = {{0, 0}, {0.2f, 0}, {-0.2f, 0}, {0, 0.2f}, {0, -0.2f}};
        for (const auto& o : offsets) {
            const b3RayResult hit = b3World_CastRayClosest(world, {bp.x + o[0], bp.y, bp.z + o[1]},
                                                           {0, -(kHalfLine + kRadius + 0.12f), 0}, feetFilter);
            if (hit.hit) grounded = true;
        }
        grounded = grounded && velocity.y < 3.0f;

        const float speed = swimming ? 2.6f : 4.3f;
        const Vector3 wish = Vector3Scale(Vector3Add(Vector3Scale(forward, move.y), Vector3Scale(right, move.x)), speed);
        b3Vec3 v = velocity;
        const float control = grounded || swimming ? 1.0f : 0.12f;  // a little steering in the air
        v.x = Lerp(v.x, wish.x, control);
        v.z = Lerp(v.z, wish.z, control);

        // Walking into a one-block step jumps it, like the auto-jump of touch-screen block games.
        bool autoJump = false;
        if (grounded && Vector3Length(wish) > 0.5f) {
            const Vector3 ahead = Vector3Add(body, Vector3Scale(Vector3Normalize(wish), kRadius + 0.3f));
            const float feet = body.y - kHalfLine - kRadius + 0.5f;
            autoJump = solidAt({ahead.x, feet, ahead.z}) && !solidAt({ahead.x, feet + 1, ahead.z}) &&
                       !solidAt({ahead.x, feet + 2, ahead.z}) && !solidAt({body.x, feet + 2, body.z});
        }
        if (swimming) {
            v.y *= 0.9f;
            if (jumpHeld) v.y = 3.0f;
        } else if (grounded && (jumpHeld || autoJump)) {
            v.y = 7.2f;  // about 1.3 blocks with the player's double gravity
        }
        b3Body_SetGravityScale(player, swimming ? 0.3f : 2.0f);
        b3Body_SetLinearVelocity(player, v);
        if (body.y < -20.0f) {  // fell off the edge of the world
            b3Body_SetTransform(player, spawn, b3Quat_identity);
            b3Body_SetLinearVelocity(player, {0, 0, 0});
        }

        // --- Throwing --------------------------------------------------------------------------
        const Vector3 eye{body.x, body.y + kEye, body.z};
        const Vector3 look{std::sin(yaw) * std::cos(pitch), std::sin(pitch), std::cos(yaw) * std::cos(pitch)};
        if (toss) {
            // Start in front of the face, but never inside a block.
            float reach = 1.0f;
            while (reach > 0.35f && solidAt(Vector3Add(eye, Vector3Scale(look, reach)))) reach -= 0.1f;
            const Vector3 at = Vector3Add(eye, Vector3Scale(look, reach));
            const b3Vec3 throwVelocity{look.x * 9.0f + v.x, look.y * 9.0f + 1.5f, look.z * 9.0f + v.z};
            things.push_back(makeThing(world, static_cast<Item>(selected), {at.x, at.y, at.z}, throwVelocity, yaw, serial++));
            if (static_cast<int>(things.size()) > kMaxThings) {  // keep the phone fast: forget the oldest
                b3DestroyBody(things.front().body);
                things.erase(things.begin());
            }
        }

        // --- Physics ---------------------------------------------------------------------------
        for (size_t i = 0; i < things.size();) {
            Thing& t = things[i];
            const b3Vec3 p = b3Body_GetPosition(t.body);
            // Floating: push up by the weight of the water the thing displaces.
            const float depth = waterAt(p.x, p.z) ? Clamp((kWaterTop - (p.y - t.bottom)) / (2 * t.bottom), 0.0f, 1.0f) : 0.0f;
            if ((depth > 0.0f) != t.wet) {
                t.wet = depth > 0.0f;
                b3Body_SetLinearDamping(t.body, t.wet ? 2.5f : (t.item == Item::BeachBall ? 0.3f : 0.0f));
                b3Body_SetAngularDamping(t.body, t.wet ? 2.0f : 0.0f);
            }
            // Water has density 1 here, like the shapes' densities (a crate at 0.6 floats, a ball at 2 sinks).
            if (t.wet) b3Body_ApplyForceToCenter(t.body, {0, t.volume * depth * 10.0f, 0}, true);
            bool gone = p.y < -20.0f;
            if (t.item == Item::Bomb && (t.fuse -= dt) <= 0.0f) {
                b3ExplosionDef blast = b3DefaultExplosionDef();
                blast.position = p;
                blast.radius = 2.5f;
                blast.falloff = 2.5f;
                blast.impulsePerArea = 3.5f;
                blast.maskBits = kThingBit | kPlayerBit;
                b3World_Explode(world, &blast);
                blasts.push_back({{p.x, p.y, p.z}, 0.0f});
                gone = true;
            }
            if (gone) {
                b3DestroyBody(t.body);
                things.erase(things.begin() + static_cast<long>(i));
            } else {
                ++i;
            }
        }
        b3World_Step(world, dt, 4);

        // --- Drawing ---------------------------------------------------------------------------
        const b3Vec3 after = b3Body_GetPosition(player);
        camera.position = {after.x, after.y + kEye, after.z};
        camera.target = Vector3Add(camera.position, look);
        SetShaderValue(shader, viewPosLoc, &camera.position, SHADER_UNIFORM_VEC3);

        BeginDrawing();
        ClearBackground(sky);
        BeginMode3D(camera);
        // Skip chunks lost in the fog or behind the camera: about half of them, most of the time.
        std::vector<bool> visible(chunks.size());
        for (size_t i = 0; i < chunks.size(); ++i) {
            const float cx = (static_cast<int>(i) / kChunksZ + 0.5f) * kChunk - camera.position.x;
            const float cz = (static_cast<int>(i) % kChunksZ + 0.5f) * kChunk - camera.position.z;
            const float reach = fogRange[1] + kChunk;  // + a chunk's half diagonal, rounded up
            visible[i] = cx * cx + cz * cz < reach * reach && cx * forward.x + cz * forward.z > -kChunk;
            if (visible[i] && chunks[i].land.vertexCount > 0) DrawMesh(chunks[i].land, material, MatrixIdentity());
        }
        const float cloudSpan = kCloudCells * kCloudCell, drift = std::fmod(time * 1.2f, cloudSpan);
        for (int copy = -1; copy <= 0; ++copy) {
            const float x = kSizeX / 2.0f - cloudSpan / 2 + drift + copy * cloudSpan;
            DrawMesh(clouds, material, MatrixTranslate(x, 0, kSizeZ / 2.0f - cloudSpan / 2));
        }
        for (const Thing& t : things) {
            const b3Vec3 p = b3Body_GetPosition(t.body);
            drawShadow({p.x, p.y, p.z}, t.item == Item::Plank ? 0.6f : t.bottom);
        }
        for (const Thing& t : things) drawThing(t, time);
        for (Blast& b : blasts) {
            b.age += dt;
            const float k = b.age / 0.35f;
            DrawSphereEx(b.at, 0.4f + 2.6f * k, 8, 10, Fade(Color{255, 200, 90, 255}, 0.8f * (1.0f - k)));
        }
        blasts.erase(std::remove_if(blasts.begin(), blasts.end(), [](const Blast& b) { return b.age > 0.35f; }), blasts.end());
        for (size_t i = 0; i < chunks.size(); ++i) {  // water last, so what is under it shows through
            if (visible[i] && chunks[i].water.vertexCount > 0) DrawMesh(chunks[i].water, material, MatrixIdentity());
        }
        EndMode3D();

        // Underwater tint and the crosshair.
        if (camera.position.y < kWaterTop && waterAt(camera.position.x, camera.position.z)) DrawRectangle(0, 0, static_cast<int>(w), static_cast<int>(h), Color{30, 80, 160, 110});
        const float cross = 9 * ui.unit, thick = std::max(2.0f, 2 * ui.unit);
        DrawRectangleV({w / 2 - cross, h / 2 - thick / 2}, {2 * cross, thick}, Fade(WHITE, 0.85f));
        DrawRectangleV({w / 2 - thick / 2, h / 2 - cross}, {thick, 2 * cross}, Fade(WHITE, 0.85f));

        // Move pad.
        DrawCircleV(ui.pad, ui.padRadius, Fade(BLACK, 0.22f));
        DrawCircleLinesV(ui.pad, ui.padRadius, Fade(WHITE, 0.5f));
        const Vector2 knob = Vector2Add(ui.pad, Vector2Scale({move.x, -move.y}, ui.padRadius * 0.6f));
        DrawCircleV(knob, 22 * ui.unit, Fade(WHITE, 0.55f));

        // Jump and throw.
        DrawCircleV(ui.jump, ui.jumpRadius, Fade(jumpHeld ? WHITE : BLACK, jumpHeld ? 0.45f : 0.25f));
        DrawCircleLinesV(ui.jump, ui.jumpRadius, Fade(WHITE, 0.6f));
        const float a = 12 * ui.unit;
        DrawTriangle({ui.jump.x, ui.jump.y - a}, {ui.jump.x - a, ui.jump.y + a * 0.6f}, {ui.jump.x + a, ui.jump.y + a * 0.6f},
                     Fade(WHITE, 0.85f));
        DrawCircleV(ui.toss, ui.tossRadius, Fade(BLACK, 0.25f));
        DrawCircleLinesV(ui.toss, ui.tossRadius, Fade(WHITE, 0.6f));
        drawItemIcon(static_cast<Item>(selected), ui.toss, ui.tossRadius * 1.5f);

        // Hotbar.
        for (int i = 0; i < kItemCount; ++i) {
            const Rectangle r = ui.slots[i];
            DrawRectangleRec(r, Fade(BLACK, i == selected ? 0.5f : 0.3f));
            DrawRectangleLinesEx(r, i == selected ? 3 * ui.unit : ui.unit, Fade(WHITE, i == selected ? 1.0f : 0.5f));
            drawItemIcon(static_cast<Item>(i), {r.x + r.width / 2, r.y + r.height / 2}, r.width);
            DrawText(TextFormat("%d", i + 1), static_cast<int>(r.x + 3 * ui.unit), static_cast<int>(r.y + 2 * ui.unit),
                     ui.textSize * 3 / 4, Fade(WHITE, 0.8f));
        }
        const char* name = kItemNames[selected];
        const int nameWidth = MeasureText(name, ui.textSize);
        DrawText(name, static_cast<int>(w / 2 - nameWidth / 2.0f), static_cast<int>(ui.slots[0].y - ui.textSize - 6 * ui.unit),
                 ui.textSize, WHITE);

        // Top bar: status and Clear.
        DrawText(TextFormat("FPS %d   Things %d/%d", GetFPS(), static_cast<int>(things.size()), kMaxThings),
                 static_cast<int>(ui.margin), static_cast<int>(ui.margin), ui.textSize, WHITE);
        DrawRectangleRounded(ui.clear, 0.3f, 6, Fade(BLACK, 0.3f));
        const int clearWidth = MeasureText("Clear", ui.textSize);
        DrawText("Clear", static_cast<int>(ui.clear.x + (ui.clear.width - clearWidth) / 2),
                 static_cast<int>(ui.clear.y + (ui.clear.height - ui.textSize) / 2), ui.textSize, WHITE);
        EndDrawing();
    }

    for (Chunk& chunk : chunks) {
        if (chunk.land.vertexCount > 0) UnloadMesh(chunk.land);
        if (chunk.water.vertexCount > 0) UnloadMesh(chunk.water);
    }
    if (clouds.vertexCount > 0) UnloadMesh(clouds);
    UnloadShader(shader);
    b3DestroyWorld(world);
    b3DestroyMesh(terrainMesh);
    b3DestroyHull(gBarrelHull);
    CloseWindow();
    return 0;
}
