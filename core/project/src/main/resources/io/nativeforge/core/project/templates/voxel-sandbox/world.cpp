// The world: block types and their textures, endless terrain from noise, chunk meshes and the
// Box3D colliders of the chunks near the player.
#include "game.hpp"

#include "rlgl.h"

#include <algorithm>
#include <cmath>
#include <cstring>

// ---------------------------------------------------------------------------------------------
// Block table and texture atlas. Every texture is drawn by code below: no image files.

enum Tile {
    TGrassTop, TGrassSide, TDirt, TStone, TCobble, TSand, TGravel, TLogSide, TLogTop, TPlanks, TLeaves,
    TGlass, TBrick, TSnow, TSnowSide, TIce, TBasalt, TWater, TLamp, TCloth, TileCount = TCloth + 9
};
constexpr int kTile = 16, kAtlasColumns = 8, kAtlasRows = 4;

static const BlockInfo kBlocks[BlockCount] = {
    {"Air", 0, 0, 0, false, false, false, false},
    {"Grass", TGrassTop, TGrassSide, TDirt, true, true, false, true},
    {"Dirt", TDirt, TDirt, TDirt, true, true, false, true},
    {"Stone", TStone, TStone, TStone, true, true, false, true},
    {"Cobblestone", TCobble, TCobble, TCobble, true, true, false, true},
    {"Sand", TSand, TSand, TSand, true, true, false, true},
    {"Gravel", TGravel, TGravel, TGravel, true, true, false, true},
    {"Log", TLogTop, TLogSide, TLogTop, true, true, false, true},
    {"Planks", TPlanks, TPlanks, TPlanks, true, true, false, true},
    {"Leaves", TLeaves, TLeaves, TLeaves, true, true, false, true},
    {"Glass", TGlass, TGlass, TGlass, true, false, true, true},
    {"Brick", TBrick, TBrick, TBrick, true, true, false, true},
    {"Snow", TSnow, TSnowSide, TDirt, true, true, false, true},
    {"Ice", TIce, TIce, TIce, true, true, false, true},
    {"Basalt", TBasalt, TBasalt, TBasalt, true, true, false, false},
    {"Water", TWater, TWater, TWater, false, false, true, false},
    {"Lamp", TLamp, TLamp, TLamp, true, true, false, true},
    {"Red cloth", TCloth + 0, TCloth + 0, TCloth + 0, true, true, false, true},
    {"Orange cloth", TCloth + 1, TCloth + 1, TCloth + 1, true, true, false, true},
    {"Yellow cloth", TCloth + 2, TCloth + 2, TCloth + 2, true, true, false, true},
    {"Green cloth", TCloth + 3, TCloth + 3, TCloth + 3, true, true, false, true},
    {"Cyan cloth", TCloth + 4, TCloth + 4, TCloth + 4, true, true, false, true},
    {"Blue cloth", TCloth + 5, TCloth + 5, TCloth + 5, true, true, false, true},
    {"Purple cloth", TCloth + 6, TCloth + 6, TCloth + 6, true, true, false, true},
    {"White cloth", TCloth + 7, TCloth + 7, TCloth + 7, true, true, false, true},
    {"Black cloth", TCloth + 8, TCloth + 8, TCloth + 8, true, true, false, true},
};

const BlockInfo& blockInfo(uint8_t block) { return kBlocks[block < BlockCount ? block : 0]; }

static const Color kClothColors[9] = {{196, 52, 46, 255},   {232, 128, 38, 255}, {236, 204, 52, 255},
                                      {88, 164, 52, 255},   {44, 152, 164, 255}, {52, 76, 176, 255},
                                      {128, 56, 168, 255},  {232, 232, 228, 255}, {36, 36, 42, 255}};

static Color gTileAverage[TileCount];

Color blockColor(uint8_t block) { return gTileAverage[blockInfo(block).side]; }

// Small integer hash: the same inputs always give the same "random" number.
static uint32_t hash3(int x, int y, int z) {
    uint32_t h = 20261125u ^ (static_cast<uint32_t>(x) * 374761393u) ^ (static_cast<uint32_t>(y) * 2246822519u) ^
                 (static_cast<uint32_t>(z) * 668265263u);
    h = (h ^ (h >> 13)) * 1274126177u;
    return h ^ (h >> 16);
}
static float random01(int x, int y, int z) { return (hash3(x, y, z) & 0xFFFFFF) / 16777215.0f; }

static Color tint(Color c, float k) {
    return {static_cast<unsigned char>(Clamp(c.r * k, 0, 255)), static_cast<unsigned char>(Clamp(c.g * k, 0, 255)),
            static_cast<unsigned char>(Clamp(c.b * k, 0, 255)), c.a};
}

// One 16 x 16 tile, pixel by pixel. (px, py) run from the top-left corner.
static Color tilePixel(int tile, int px, int py) {
    const float n = random01(px, py, tile * 31 + 7);         // fine grain
    const float g = 0.88f + 0.24f * n;
    switch (tile) {
        case TGrassTop: return tint({96, 160, 64, 255}, 0.85f + 0.3f * n);
        case TGrassSide: {
            const int edge = 3 + static_cast<int>(hash3(px, 0, 99) % 3);  // ragged line of grass
            return py < edge ? tint({96, 160, 64, 255}, g) : tint({128, 92, 62, 255}, g);
        }
        case TDirt: return tint({128, 92, 62, 255}, n < 0.1f ? 0.7f : g);
        case TStone: {
            const float blot = random01(px / 4, py / 3, 5);
            return tint({128, 128, 132, 255}, (blot < 0.25f ? 0.85f : 1.0f) * (0.92f + 0.16f * n));
        }
        case TCobble: {
            // Irregular stones: rows of different heights, each cut at random places, dark mortar.
            const int row = py / 4, offset = static_cast<int>(hash3(row, 1, 3) % 5);
            const int cell = (px + offset) / 5;
            const bool mortar = py % 4 == 3 || (px + offset) % 5 == 4;
            return mortar ? Color{72, 72, 76, 255} : tint({138, 138, 140, 255}, 0.8f + 0.35f * random01(cell, row, 8) + 0.08f * n);
        }
        case TSand: return tint({222, 208, 150, 255}, 0.94f + 0.12f * n);
        case TGravel: {
            const float k = random01(px / 2, py / 2, 11);
            return tint(k < 0.33f ? Color{150, 140, 132, 255} : k < 0.66f ? Color{118, 112, 108, 255} : Color{170, 160, 150, 255},
                        0.92f + 0.12f * n);
        }
        case TLogSide: return tint({104, 76, 48, 255}, (px % 4 == 0 ? 0.75f : 1.0f) * (0.9f + 0.2f * random01(px, py / 3, 13)));
        case TLogTop: {
            const float d = std::sqrt((px - 7.5f) * (px - 7.5f) + (py - 7.5f) * (py - 7.5f));
            if (d > 7.0f) return tint({104, 76, 48, 255}, g);
            return tint({176, 140, 92, 255}, (static_cast<int>(d) % 3 == 0 ? 0.82f : 1.0f) * (0.95f + 0.1f * n));
        }
        case TPlanks: {
            const int board = py / 4, seam = static_cast<int>(hash3(board, 2, 2) % 16);
            if (py % 4 == 3 || px == seam) return {124, 92, 56, 255};
            return tint({184, 146, 96, 255}, 0.9f + 0.12f * random01(px / 3, board, 17) + 0.05f * n);
        }
        case TLeaves: return tint({60, 124, 52, 255}, n < 0.18f ? 0.6f : 0.85f + 0.3f * random01(px / 2, py / 2, 19));
        case TGlass: {
            const bool frame = px == 0 || py == 0 || px == 15 || py == 15;
            const bool shine = (px - py == 4 || px - py == 5) && px > 3 && px < 12;
            if (frame) return {210, 230, 236, 230};
            if (shine) return {255, 255, 255, 140};
            return {200, 225, 235, 40};
        }
        case TBrick: {
            const int row = py / 4;
            const bool mortar = py % 4 == 3 || (px + (row % 2) * 4) % 8 == 7;
            return mortar ? Color{196, 188, 176, 255} : tint({160, 70, 54, 255}, 0.9f + 0.2f * random01((px + (row % 2) * 4) / 8, row, 23));
        }
        case TSnow: return tint({240, 244, 250, 255}, 0.96f + 0.06f * n);
        case TSnowSide: return py < 4 ? tint({240, 244, 250, 255}, 0.96f + 0.06f * n) : tint({128, 92, 62, 255}, g);
        case TIce: return tint({160, 200, 240, 255}, (px + py) % 7 == 0 ? 1.12f : 0.95f + 0.08f * n);
        case TBasalt: return tint({52, 50, 56, 255}, 0.8f + 0.4f * random01(px / 2, py / 2, 29));
        case TWater: return {255, 255, 255, static_cast<unsigned char>(150 + 30 * n)};  // coloured by the mesh
        case TLamp: {
            const bool frame = px < 2 || py < 2 || px > 13 || py > 13;
            return frame ? Color{150, 110, 50, 255} : tint({255, 222, 120, 255}, 0.95f + 0.1f * n);
        }
        default: {
            const Color c = kClothColors[std::clamp(tile - TCloth, 0, 8)];
            return tint(c, ((px + py) % 2 ? 0.94f : 1.0f) * (0.95f + 0.1f * n));  // woven
        }
    }
}

static Texture2D makeAtlas() {
    Image image = GenImageColor(kTile * kAtlasColumns, kTile * kAtlasRows, BLANK);
    for (int tile = 0; tile < TileCount; ++tile) {
        const int ox = (tile % kAtlasColumns) * kTile, oy = (tile / kAtlasColumns) * kTile;
        int r = 0, g = 0, b = 0;
        for (int py = 0; py < kTile; ++py) {
            for (int px = 0; px < kTile; ++px) {
                const Color c = tilePixel(tile, px, py);
                ImageDrawPixel(&image, ox + px, oy + py, c);
                r += c.r;
                g += c.g;
                b += c.b;
            }
        }
        gTileAverage[tile] = {static_cast<unsigned char>(r / 256), static_cast<unsigned char>(g / 256),
                              static_cast<unsigned char>(b / 256), 255};
    }
    gTileAverage[TWater] = {60, 120, 200, 255};
    Texture2D texture = LoadTextureFromImage(image);
    SetTextureFilter(texture, TEXTURE_FILTER_POINT);  // crisp pixels
    UnloadImage(image);
    return texture;
}

// Texture coordinates of a tile corner; (s, t) from 0 to 1 inside the tile.
static Vector2 tileUV(int tile, float s, float t) {
    const float inset = 0.02f;  // stay inside the tile so neighbours never bleed in
    const float u = (tile % kAtlasColumns) * kTile + inset + s * (kTile - 2 * inset);
    const float v = (tile / kAtlasColumns) * kTile + inset + t * (kTile - 2 * inset);
    return {u / (kTile * kAtlasColumns), v / (kTile * kAtlasRows)};
}

Vector2 blockUV(uint8_t block, int face, float s, float t) {
    const BlockInfo& info = blockInfo(block);
    return tileUV(face == 0 ? info.top : face == 1 ? info.side : info.bottom, s, t);
}

// ---------------------------------------------------------------------------------------------
// Terrain from noise. Everything is a function of the block's position, so any chunk can be made
// on its own, in any order, and always comes out the same.

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

static int terrainHeight(int x, int z) {
    const float hills = fractalNoise(x * 0.012f, z * 0.012f, 1);
    const float detail = fractalNoise(x * 0.06f, z * 0.06f, 9);
    const float t = Clamp((hills - 0.4f) / 0.45f, 0.0f, 1.0f);  // low ground becomes lakes and seas
    return std::clamp(static_cast<int>(8 + std::pow(t, 1.5f) * 38 + detail * 4), 2, kHeight - 12);
}

static uint8_t surfaceBlock(int height) {
    if (height < kSeaLevel - 3) return Gravel;
    if (height <= kSeaLevel) return Sand;
    if (height >= 40) return Snow;
    if (height >= 34) return Stone;
    return Grass;
}

int World::surfaceHeight(int x, int z) const { return terrainHeight(x, z); }

// ---------------------------------------------------------------------------------------------

static int64_t key(int cx, int cz) { return (static_cast<int64_t>(cx) << 32) ^ static_cast<uint32_t>(cz); }
static int floorDiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

// Vertex colours plus distance fog, so far chunks fade into the sky instead of popping in.
static const char* kVertexShader = R"(
ATTRIBUTE vec3 vertexPosition;
ATTRIBUTE vec2 vertexTexCoord;
ATTRIBUTE vec4 vertexColor;
uniform mat4 mvp;
uniform mat4 matModel;
uniform vec3 viewPos;
VARYING_OUT vec2 uv;
VARYING_OUT vec4 color;
VARYING_OUT float fogDistance;
void main() {
    vec4 world = matModel * vec4(vertexPosition, 1.0);
    uv = vertexTexCoord;
    color = vertexColor;
    fogDistance = length(world.xz - viewPos.xz);
    gl_Position = mvp * vec4(vertexPosition, 1.0);
}
)";
static const char* kFragmentShader = R"(
precision mediump float;
VARYING_IN vec2 uv;
VARYING_IN vec4 color;
VARYING_IN float fogDistance;
uniform sampler2D texture0;
uniform vec4 colDiffuse;
uniform vec3 fogColor;
uniform vec2 fogRange;
void main() {
    vec4 texel = TEXTURE(texture0, uv) * color * colDiffuse;
    if (texel.a < 0.02) discard;
    float fog = clamp((fogDistance - fogRange.x) / (fogRange.y - fogRange.x), 0.0, 1.0);
    FRAGMENT = vec4(mix(texel.rgb, fogColor, fog), texel.a);
}
)";

static Shader loadShader() {
#if defined(__ANDROID__)
    // OpenGL ES 2 (GLSL ES 1.00), what raylib uses on phones.
    const std::string vertexHeader = "#version 100\n#define ATTRIBUTE attribute\n#define VARYING_OUT varying\n";
    const std::string fragmentHeader =
        "#version 100\n#define VARYING_IN varying\n#define TEXTURE texture2D\n#define FRAGMENT gl_FragColor\n";
#else
    // Desktop OpenGL 3.3, so the same code also runs in a raylib build on a computer.
    const std::string vertexHeader = "#version 330\n#define ATTRIBUTE in\n#define VARYING_OUT out\n";
    const std::string fragmentHeader = "#version 330\n#define VARYING_IN in\n#define TEXTURE texture\n"
                                       "out vec4 fragmentColor;\n#define FRAGMENT fragmentColor\n";
#endif
    const std::string vertex = vertexHeader + kVertexShader, fragment = fragmentHeader + kFragmentShader;
    return LoadShaderFromMemory(vertex.c_str(), fragment.c_str());
}

World::World(b3WorldId physics) : physics_(physics) {
    atlas = makeAtlas();
    shader = loadShader();
    material = LoadMaterialDefault();
    material.shader = shader;
    material.maps[MATERIAL_MAP_DIFFUSE].texture = atlas;
    const float fogColor[3] = {148 / 255.0f, 200 / 255.0f, 238 / 255.0f};
    const float fogRange[2] = {kFogEnd * 0.55f, kFogEnd};
    SetShaderValue(shader, GetShaderLocation(shader, "fogColor"), fogColor, SHADER_UNIFORM_VEC3);
    SetShaderValue(shader, GetShaderLocation(shader, "fogRange"), fogRange, SHADER_UNIFORM_VEC2);
}

World::~World() {
    for (auto& entry : chunks_) {
        dropMesh(*entry.second);
        dropCollider(*entry.second);
    }
    UnloadTexture(atlas);
    UnloadShader(shader);
}

Chunk* World::find(int cx, int cz) const {
    const auto it = chunks_.find(key(cx, cz));
    return it == chunks_.end() ? nullptr : it->second.get();
}

uint8_t World::get(int x, int y, int z) const {
    if (y < 0) return Basalt;
    if (y >= kHeight) return Air;
    const Chunk* chunk = find(floorDiv(x, kChunk), floorDiv(z, kChunk));
    if (chunk == nullptr) return Stone;
    return chunk->blocks[(y * kChunk + (z - chunk->cz * kChunk)) * kChunk + (x - chunk->cx * kChunk)];
}

bool World::loaded(int x, int z) const { return find(floorDiv(x, kChunk), floorDiv(z, kChunk)) != nullptr; }

bool World::hasCollider(int x, int z) const {
    const Chunk* chunk = find(floorDiv(x, kChunk), floorDiv(z, kChunk));
    return chunk != nullptr && !chunk->colliderDirty;
}

void World::set(int x, int y, int z, uint8_t block) {
    if (y < 0 || y >= kHeight) return;
    const int cx = floorDiv(x, kChunk), cz = floorDiv(z, kChunk);
    Chunk* chunk = find(cx, cz);
    if (chunk == nullptr) return;
    const int lx = x - cx * kChunk, lz = z - cz * kChunk;
    chunk->at(lx, y, lz) = block;
    chunk->top = std::max(chunk->top, y);
    chunk->edited = true;
    // Faces and corner shading of the neighbours can change too.
    for (int dx = -1; dx <= 1; ++dx) {
        for (int dz = -1; dz <= 1; ++dz) {
            const int nx = lx + dx, nz = lz + dz;
            Chunk* other = find(floorDiv(cx * kChunk + nx, kChunk), floorDiv(cz * kChunk + nz, kChunk));
            if (other != nullptr) {
                other->meshDirty = true;
                if (other == chunk) other->colliderDirty = true;
            }
        }
    }
    if (lx == 0 || lx == kChunk - 1 || lz == 0 || lz == kChunk - 1) {
        // A face on the chunk's border belongs to the neighbour's collider.
        for (auto [dx, dz] : {std::pair{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            if (Chunk* other = find(cx + dx, cz + dz)) other->colliderDirty = true;
        }
    }
}

Chunk& World::generate(int cx, int cz) {
    auto chunk = std::make_unique<Chunk>();
    chunk->cx = cx;
    chunk->cz = cz;
    Chunk& c = *chunk;
    const int x0 = cx * kChunk, z0 = cz * kChunk;
    for (int lx = 0; lx < kChunk; ++lx) {
        for (int lz = 0; lz < kChunk; ++lz) {
            const int height = terrainHeight(x0 + lx, z0 + lz);
            const uint8_t surface = surfaceBlock(height);
            const uint8_t under = surface == Grass || surface == Snow ? Dirt : surface == Stone ? Stone : surface;
            for (int y = 0; y <= height; ++y) {
                uint8_t b = Stone;
                if (y == 0) b = Basalt;
                else if (y == height) b = surface;
                else if (y >= height - 3) b = under;
                c.at(lx, y, lz) = b;
            }
            for (int y = height + 1; y < kSeaLevel; ++y) c.at(lx, y, lz) = Water;
            c.top = std::max(c.top, std::max(height, kSeaLevel - 1));
        }
    }
    // Trees: at most one per 7 x 7 cell, on grass. Cells around the chunk count too, because a
    // tree next door can reach over the border with its leaves.
    constexpr int kCell = 7;
    for (int gx = floorDiv(x0 - 3, kCell); gx <= floorDiv(x0 + kChunk + 2, kCell); ++gx) {
        for (int gz = floorDiv(z0 - 3, kCell); gz <= floorDiv(z0 + kChunk + 2, kCell); ++gz) {
            if (hash3(gx, 3, gz) % 100 >= 35) continue;
            const int tx = gx * kCell + 2 + static_cast<int>(hash3(gx, 4, gz) % 3);
            const int tz = gz * kCell + 2 + static_cast<int>(hash3(gx, 5, gz) % 3);
            const int ground = terrainHeight(tx, tz);
            if (surfaceBlock(ground) != Grass) continue;
            const int trunk = 4 + static_cast<int>(hash3(tx, 7, tz) % 2);
            auto put = [&](int x, int y, int z, uint8_t b, bool replace) {
                const int lx = x - x0, lz = z - z0;
                if (lx < 0 || lx >= kChunk || lz < 0 || lz >= kChunk || y >= kHeight) return;
                if (replace || c.at(lx, y, lz) == Air) c.at(lx, y, lz) = b;
                c.top = std::max(c.top, y);
            };
            const int top = ground + trunk;
            for (int dy = -2; dy <= 1; ++dy) {
                const int radius = dy < 0 ? 2 : 1;
                for (int dx = -radius; dx <= radius; ++dx) {
                    for (int dz = -radius; dz <= radius; ++dz) {
                        const bool corner = std::abs(dx) == radius && std::abs(dz) == radius;
                        if (corner && (dy == 1 || hash3(tx + dx, top + dy, tz + dz) % 2)) continue;
                        put(tx + dx, top + dy, tz + dz, Leaves, false);
                    }
                }
            }
            for (int y = 1; y <= trunk; ++y) put(tx, ground + y, tz, Log, true);
        }
    }
    Chunk& result = *chunk;
    chunks_[key(cx, cz)] = std::move(chunk);
    return result;
}

// ---------------------------------------------------------------------------------------------
// Meshing: only faces between a block and something see-through are kept, with a fixed light per
// direction and darker corners where blocks meet (ambient occlusion).

namespace {
struct Builder {
    std::vector<float> positions, uvs;
    std::vector<unsigned char> colors;

    void vertex(Vector3 p, Vector2 uv, Color c) {
        positions.insert(positions.end(), {p.x, p.y, p.z});
        uvs.insert(uvs.end(), {uv.x, uv.y});
        colors.insert(colors.end(), {c.r, c.g, c.b, c.a});
    }

    Mesh upload() const {
        Mesh mesh{};
        mesh.vertexCount = static_cast<int>(positions.size() / 3);
        mesh.triangleCount = mesh.vertexCount / 3;
        if (mesh.vertexCount == 0) return mesh;
        mesh.vertices = static_cast<float*>(MemAlloc(positions.size() * sizeof(float)));
        mesh.texcoords = static_cast<float*>(MemAlloc(uvs.size() * sizeof(float)));
        mesh.colors = static_cast<unsigned char*>(MemAlloc(colors.size()));
        std::memcpy(mesh.vertices, positions.data(), positions.size() * sizeof(float));
        std::memcpy(mesh.texcoords, uvs.data(), uvs.size() * sizeof(float));
        std::memcpy(mesh.colors, colors.data(), colors.size());
        UploadMesh(&mesh, false);
        // The GPU has its copy; free ours.
        MemFree(mesh.vertices);
        MemFree(mesh.texcoords);
        MemFree(mesh.colors);
        mesh.vertices = mesh.texcoords = nullptr;
        mesh.colors = nullptr;
        return mesh;
    }
};

// The chunk and a one-block border taken from its neighbours, so meshing never looks blocks up
// in the map.
struct Padded {
    static constexpr int N = kChunk + 2;
    std::vector<uint8_t> b = std::vector<uint8_t>(N * N * kHeight, Air);
    uint8_t get(int x, int y, int z) const {  // -1 .. 16
        if (y < 0) return Basalt;
        if (y >= kHeight) return Air;
        return b[(y * N + (z + 1)) * N + (x + 1)];
    }
};
}  // namespace

void World::buildMesh(Chunk& chunk) {
    Padded p;
    for (int x = -1; x <= kChunk; ++x) {
        for (int z = -1; z <= kChunk; ++z) {
            const int wx = chunk.cx * kChunk + x, wz = chunk.cz * kChunk + z;
            const Chunk* source = find(floorDiv(wx, kChunk), floorDiv(wz, kChunk));
            for (int y = 0; y < kHeight; ++y) {
                p.b[(y * Padded::N + (z + 1)) * Padded::N + (x + 1)] =
                    source ? source->blocks[(y * kChunk + (wz - source->cz * kChunk)) * kChunk + (wx - source->cx * kChunk)] : Stone;
            }
        }
    }
    Builder opaque, translucent;
    static const float kFaceLight[3][2] = {{0.7f, 0.8f}, {0.5f, 1.0f}, {0.6f, 0.9f}};  // [axis][negative, positive]
    static const float kOcclusion[4] = {0.5f, 0.68f, 0.84f, 1.0f};
    static const int kCornerUV[4][2] = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    const float ox = static_cast<float>(chunk.cx * kChunk), oz = static_cast<float>(chunk.cz * kChunk);
    const int top = std::min(chunk.top + 1, kHeight - 1);

    for (int y = 0; y <= top; ++y) {
        for (int z = 0; z < kChunk; ++z) {
            for (int x = 0; x < kChunk; ++x) {
                const uint8_t block = p.get(x, y, z);
                if (block == Air) continue;
                const BlockInfo& info = blockInfo(block);
                // Water's surface sits a little lower than a full block.
                const bool lowTop = block == Water && p.get(x, y + 1, z) != Water;
                for (int axis = 0; axis < 3; ++axis) {
                    for (int sign = -1; sign <= 1; sign += 2) {
                        int n[3] = {x, y, z};
                        n[axis] += sign;
                        const uint8_t neighbour = p.get(n[0], n[1], n[2]);
                        const BlockInfo& other = blockInfo(neighbour);
                        if (other.opaque || neighbour == block) continue;
                        const int u = (axis + 1) % 3, v = (axis + 2) % 3;
                        const int tile = axis == 1 ? (sign > 0 ? info.top : info.bottom) : info.side;
                        const float light = kFaceLight[axis][sign > 0 ? 1 : 0];
                        Vector3 corners[4];
                        Vector2 uvs[4];
                        float occlusion[4];
                        for (int i = 0; i < 4; ++i) {
                            float c[3] = {static_cast<float>(x), static_cast<float>(y), static_cast<float>(z)};
                            c[axis] += sign > 0 ? 1.0f : 0.0f;
                            c[u] += kCornerUV[i][0];
                            c[v] += kCornerUV[i][1];
                            if (lowTop && c[1] > y + 0.5f) c[1] = y + 0.88f;
                            corners[i] = {ox + c[0], c[1], oz + c[2]};
                            // Texture: sides upright (t grows downwards), tops in x/z.
                            const float cu = static_cast<float>(kCornerUV[i][0]), cv = static_cast<float>(kCornerUV[i][1]);
                            if (axis == 0) uvs[i] = tileUV(tile, cv, 1 - cu);
                            else if (axis == 2) uvs[i] = tileUV(tile, cu, 1 - cv);
                            else uvs[i] = tileUV(tile, cv, cu);
                            // Corner shading from the three blocks touching the corner in front of the face.
                            const int su = kCornerUV[i][0] ? 1 : -1, sv = kCornerUV[i][1] ? 1 : -1;
                            int a[3] = {n[0], n[1], n[2]}, b[3] = {n[0], n[1], n[2]}, d[3] = {n[0], n[1], n[2]};
                            a[u] += su;
                            b[v] += sv;
                            d[u] += su;
                            d[v] += sv;
                            const int s1 = blockInfo(p.get(a[0], a[1], a[2])).opaque;
                            const int s2 = blockInfo(p.get(b[0], b[1], b[2])).opaque;
                            const int cc = blockInfo(p.get(d[0], d[1], d[2])).opaque;
                            occlusion[i] = kOcclusion[s1 && s2 ? 0 : 3 - s1 - s2 - cc];
                        }
                        int order[4] = {0, 1, 2, 3};
                        if (sign < 0) std::swap(order[1], order[3]);  // the back side winds the other way
                        if (occlusion[order[0]] + occlusion[order[2]] < occlusion[order[1]] + occlusion[order[3]]) {
                            std::rotate(order, order + 1, order + 4);  // split along the lighter diagonal
                        }
                        Builder& out = info.translucent ? translucent : opaque;
                        const Color base = block == Water ? Color{70, 130, 210, 255}
                                         : block == Lamp ? Color{255, 255, 255, 255}
                                                         : Color{255, 255, 255, 255};
                        const float glow = block == Lamp ? 1.0f : light;  // lamps ignore the shading
                        for (int t : {0, 1, 2, 0, 2, 3}) {
                            const int i = order[t];
                            const float k = block == Lamp ? 1.0f : glow * occlusion[i];
                            out.vertex(corners[i], uvs[i], tint(base, k));
                        }
                    }
                }
            }
        }
    }
    dropMesh(chunk);
    chunk.opaque = opaque.upload();
    chunk.translucent = translucent.upload();
    chunk.hasMesh = true;
    chunk.meshDirty = false;
}

void World::dropMesh(Chunk& chunk) {
    if (chunk.opaque.vertexCount > 0) UnloadMesh(chunk.opaque);
    if (chunk.translucent.vertexCount > 0) UnloadMesh(chunk.translucent);
    chunk.opaque = Mesh{};
    chunk.translucent = Mesh{};
    chunk.hasMesh = false;
    chunk.meshDirty = true;
}

// The chunk's solid faces as one Box3D triangle mesh on a static body.
void World::buildCollider(Chunk& chunk) {
    dropCollider(chunk);
    std::vector<b3Vec3> triangles;
    const float ox = static_cast<float>(chunk.cx * kChunk), oz = static_cast<float>(chunk.cz * kChunk);
    static const int kCornerUV[4][2] = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    for (int y = 0; y <= chunk.top; ++y) {
        for (int z = 0; z < kChunk; ++z) {
            for (int x = 0; x < kChunk; ++x) {
                if (!blockInfo(chunk.at(x, y, z)).solid) continue;
                for (int axis = 0; axis < 3; ++axis) {
                    for (int sign = -1; sign <= 1; sign += 2) {
                        int n[3] = {x, y, z};
                        n[axis] += sign;
                        if (n[1] < 0) continue;
                        const bool inside = n[0] >= 0 && n[0] < kChunk && n[2] >= 0 && n[2] < kChunk && n[1] < kHeight;
                        const bool open = inside ? !blockInfo(chunk.at(n[0], n[1], n[2])).solid
                                                 : !solid(chunk.cx * kChunk + n[0], n[1], chunk.cz * kChunk + n[2]);
                        if (!open) continue;
                        const int u = (axis + 1) % 3, v = (axis + 2) % 3;
                        b3Vec3 corners[4];
                        for (int i = 0; i < 4; ++i) {
                            float c[3] = {static_cast<float>(x), static_cast<float>(y), static_cast<float>(z)};
                            c[axis] += sign > 0 ? 1.0f : 0.0f;
                            c[u] += kCornerUV[i][0];
                            c[v] += kCornerUV[i][1];
                            corners[i] = {ox + c[0], c[1], oz + c[2]};
                        }
                        const int order[6] = {0, 1, 2, 0, 2, 3}, back[6] = {0, 2, 1, 0, 3, 2};
                        for (int i : sign > 0 ? order : back) triangles.push_back(corners[i]);
                    }
                }
            }
        }
    }
    chunk.colliderDirty = false;
    if (triangles.empty()) return;
    std::vector<int32_t> indices(triangles.size());
    for (size_t i = 0; i < indices.size(); ++i) indices[i] = static_cast<int32_t>(i);
    b3MeshDef def{};
    def.vertices = triangles.data();
    def.indices = indices.data();
    def.vertexCount = static_cast<int>(triangles.size());
    def.triangleCount = static_cast<int>(triangles.size() / 3);
    def.weldVertices = true;   // shared corners make one connected surface...
    def.weldTolerance = 0.001f;
    def.identifyEdges = true;  // ...so things roll over the seams between blocks without bumps
    def.useMedianSplit = true;
    chunk.collision = b3CreateMesh(&def, nullptr, 0);
    b3BodyDef bodyDef = b3DefaultBodyDef();  // static
    chunk.body = b3CreateBody(physics_, &bodyDef);
    b3ShapeDef shape = b3DefaultShapeDef();
    shape.filter.categoryBits = kTerrainBit;
    shape.baseMaterial.friction = 0.7f;
    b3CreateMeshShape(chunk.body, &shape, chunk.collision, {1.0f, 1.0f, 1.0f});
}

void World::dropCollider(Chunk& chunk) {
    if (B3_IS_NON_NULL(chunk.body)) b3DestroyBody(chunk.body);
    chunk.body = b3_nullBodyId;
    if (chunk.collision != nullptr) b3DestroyMesh(chunk.collision);
    chunk.collision = nullptr;
    chunk.colliderDirty = true;
}

// ---------------------------------------------------------------------------------------------
// Streaming

void World::prepareAround(Vector3 player) {
    const int pcx = floorDiv(static_cast<int>(std::floor(player.x)), kChunk);
    const int pcz = floorDiv(static_cast<int>(std::floor(player.z)), kChunk);
    for (int dx = -kColliderChunks - 1; dx <= kColliderChunks + 1; ++dx)
        for (int dz = -kColliderChunks - 1; dz <= kColliderChunks + 1; ++dz)
            if (!find(pcx + dx, pcz + dz)) generate(pcx + dx, pcz + dz);
    for (int dx = -kColliderChunks; dx <= kColliderChunks; ++dx) {
        for (int dz = -kColliderChunks; dz <= kColliderChunks; ++dz) {
            Chunk& c = *find(pcx + dx, pcz + dz);
            buildMesh(c);
            buildCollider(c);
        }
    }
}

void World::update(Vector3 player, Vector3 forward) {
    const int pcx = floorDiv(static_cast<int>(std::floor(player.x)), kChunk);
    const int pcz = floorDiv(static_cast<int>(std::floor(player.z)), kChunk);
    const double start = GetTime();
    const double budget = 0.006;  // seconds of work per frame for new chunks

    // Closest first; ahead of the camera before behind it.
    struct Candidate { int cx, cz; float score; };
    std::vector<Candidate> wanted;
    const int reach = kViewChunks + 1;
    for (int dx = -reach; dx <= reach; ++dx) {
        for (int dz = -reach; dz <= reach; ++dz) {
            const float ahead = (dx * forward.x + dz * forward.z) > 0 ? 0.0f : 1.5f;
            wanted.push_back({pcx + dx, pcz + dz, std::sqrt(static_cast<float>(dx * dx + dz * dz)) + ahead});
        }
    }
    std::sort(wanted.begin(), wanted.end(), [](const Candidate& a, const Candidate& b) { return a.score < b.score; });

    for (const Candidate& c : wanted) {  // block data, one ring wider than what is drawn
        if (find(c.cx, c.cz) == nullptr) {
            generate(c.cx, c.cz);
            if (GetTime() - start > budget * 0.5) break;
        }
    }
    int meshed = 0;
    for (const Candidate& c : wanted) {
        if (std::max(std::abs(c.cx - pcx), std::abs(c.cz - pcz)) > kViewChunks) continue;
        Chunk* chunk = find(c.cx, c.cz);
        if (chunk == nullptr || !chunk->meshDirty) continue;
        bool neighbours = true;
        for (int dx = -1; dx <= 1 && neighbours; ++dx)
            for (int dz = -1; dz <= 1 && neighbours; ++dz) neighbours = find(c.cx + dx, c.cz + dz) != nullptr;
        if (!neighbours) continue;
        const bool edit = chunk->hasMesh;  // the player changed it: redo it now, whatever the budget
        if (!edit && (meshed >= 4 || GetTime() - start > budget)) continue;
        buildMesh(*chunk);
        if (!edit) ++meshed;
    }
    // Colliders near the player; far ones go (things there sleep until it comes back).
    for (auto& entry : chunks_) {
        Chunk& chunk = *entry.second;
        const int d = std::max(std::abs(chunk.cx - pcx), std::abs(chunk.cz - pcz));
        const bool stale = chunk.colliderDirty && (d <= kColliderChunks || (d == kColliderChunks + 1 && B3_IS_NON_NULL(chunk.body)));
        if (stale) buildCollider(chunk);
        else if (d > kColliderChunks + 1 && B3_IS_NON_NULL(chunk.body)) dropCollider(chunk);
    }
    // Forget what is far away, except what the player built.
    for (auto it = chunks_.begin(); it != chunks_.end();) {
        Chunk& chunk = *it->second;
        const int d = std::max(std::abs(chunk.cx - pcx), std::abs(chunk.cz - pcz));
        if (d > kViewChunks + 2) {
            dropMesh(chunk);
            dropCollider(chunk);
            if (!chunk.edited) {
                it = chunks_.erase(it);
                continue;
            }
        }
        ++it;
    }
}

bool World::visible(const Chunk& chunk, Vector3 eye, Vector3 forward) const {
    const float cx = (chunk.cx + 0.5f) * kChunk - eye.x, cz = (chunk.cz + 0.5f) * kChunk - eye.z;
    const float reach = kFogEnd + kChunk * 0.75f;
    return cx * cx + cz * cz < reach * reach && cx * forward.x + cz * forward.z > -kChunk;
}

void World::draw(Camera3D camera, Vector3 forward) {
    SetShaderValue(shader, GetShaderLocation(shader, "viewPos"), &camera.position, SHADER_UNIFORM_VEC3);
    for (auto& entry : chunks_) {
        const Chunk& chunk = *entry.second;
        if (chunk.hasMesh && chunk.opaque.vertexCount > 0 && visible(chunk, camera.position, forward)) {
            DrawMesh(chunk.opaque, material, MatrixIdentity());
        }
    }
}

void World::drawTranslucent(Camera3D camera, Vector3 forward) {
    for (auto& entry : chunks_) {
        const Chunk& chunk = *entry.second;
        if (chunk.hasMesh && chunk.translucent.vertexCount > 0 && visible(chunk, camera.position, forward)) {
            DrawMesh(chunk.translucent, material, MatrixIdentity());
        }
    }
}

// Walks the ray through the grid one block at a time (Amanatides & Woo), skipping air and water.
bool World::raycast(Vector3 origin, Vector3 direction, float reach, RayHit& hit) const {
    int x = static_cast<int>(std::floor(origin.x)), y = static_cast<int>(std::floor(origin.y)),
        z = static_cast<int>(std::floor(origin.z));
    const int stepX = direction.x > 0 ? 1 : -1, stepY = direction.y > 0 ? 1 : -1, stepZ = direction.z > 0 ? 1 : -1;
    auto boundary = [](float p, float d) {
        if (d == 0.0f) return INFINITY;
        const float next = d > 0 ? std::floor(p) + 1 - p : p - std::floor(p);
        return next / std::fabs(d);
    };
    float tMaxX = boundary(origin.x, direction.x), tMaxY = boundary(origin.y, direction.y),
          tMaxZ = boundary(origin.z, direction.z);
    const float tDeltaX = direction.x != 0 ? 1 / std::fabs(direction.x) : INFINITY;
    const float tDeltaY = direction.y != 0 ? 1 / std::fabs(direction.y) : INFINITY;
    const float tDeltaZ = direction.z != 0 ? 1 / std::fabs(direction.z) : INFINITY;
    int nx = 0, ny = 0, nz = 0;
    float t = 0;
    while (t <= reach) {
        const uint8_t b = (y >= 0 && y < kHeight) ? get(x, y, z) : Air;
        if (b != Air && b != Water) {
            hit = {x, y, z, nx, ny, nz, t};
            return true;
        }
        if (tMaxX < tMaxY && tMaxX < tMaxZ) {
            x += stepX;
            t = tMaxX;
            tMaxX += tDeltaX;
            nx = -stepX, ny = 0, nz = 0;
        } else if (tMaxY < tMaxZ) {
            y += stepY;
            t = tMaxY;
            tMaxY += tDeltaY;
            nx = 0, ny = -stepY, nz = 0;
        } else {
            z += stepZ;
            t = tMaxZ;
            tMaxZ += tDeltaZ;
            nx = 0, ny = 0, nz = -stepZ;
        }
    }
    return false;
}
