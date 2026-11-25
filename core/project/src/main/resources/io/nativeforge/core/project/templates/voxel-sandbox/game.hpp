// Shared declarations of the voxel sandbox. Each part of the game lives in its own file:
//   world.cpp   blocks, endless terrain made in chunks, meshes, block textures, Box3D colliders
//   player.cpp  walking, jumping, flying and sneaking against the blocks (box collision)
//   things.cpp  the physical things you throw: Box3D bodies, drawing, water, bombs
//   ui.cpp      touch controls, hotbar, creative inventory, first-person hand
//   main.cpp    the game loop that ties them together
#pragma once

#include "raylib.h"
#include "raymath.h"
#include <box3d/box3d.h>

#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

// ---------------------------------------------------------------------------------------------
// Blocks

enum Block : uint8_t {
    Air, Grass, Dirt, Stone, Cobblestone, Sand, Gravel, Log, Planks, Leaves, Glass, Brick, Snow, Ice,
    Basalt, Water, Lamp, RedCloth, OrangeCloth, YellowCloth, GreenCloth, CyanCloth, BlueCloth, PurpleCloth,
    WhiteCloth, BlackCloth, BlockCount
};

struct BlockInfo {
    const char* name;
    int top, side, bottom;  // tiles in the texture atlas
    bool solid;             // the player and things collide with it
    bool opaque;            // hides the faces of the blocks next to it
    bool translucent;       // drawn after everything else, blended
    bool breakable;
};

const BlockInfo& blockInfo(uint8_t block);
Color blockColor(uint8_t block);  // average colour of its side, for icons and debris
// Texture coordinates in the atlas of one face (0 top, 1 side, 2 bottom); s and t from 0 to 1.
Vector2 blockUV(uint8_t block, int face, float s, float t);

// ---------------------------------------------------------------------------------------------
// World: an endless grid of blocks, made and kept in 16 x 16 columns called chunks.

constexpr int kChunk = 16;
constexpr int kHeight = 64;          // build limit
constexpr int kSeaLevel = 16;        // water fills everything lower than this
constexpr int kViewChunks = 5;       // chunks drawn in every direction
constexpr int kColliderChunks = 2;   // chunks around the player that have Box3D colliders
constexpr float kFogEnd = kViewChunks * kChunk - 4.0f;

constexpr uint64_t kTerrainBit = 1, kThingBit = 2, kPlayerBit = 4, kDebrisBit = 8;

struct Chunk {
    int cx = 0, cz = 0;
    std::vector<uint8_t> blocks = std::vector<uint8_t>(kChunk * kHeight * kChunk, Air);
    int top = 0;              // highest non-air block, to mesh less
    bool edited = false;      // changed by the player: kept in memory when far away
    bool meshDirty = true;
    bool colliderDirty = true;
    Mesh opaque{}, translucent{};
    bool hasMesh = false;
    b3BodyId body = b3_nullBodyId;
    b3MeshData* collision = nullptr;

    uint8_t& at(int x, int y, int z) { return blocks[(y * kChunk + z) * kChunk + x]; }
};

struct RayHit {
    int x, y, z;     // the block hit
    int nx, ny, nz;  // the face hit, as a direction: the new block goes at (x+nx, y+ny, z+nz)
    float distance;
};

class World {
public:
    explicit World(b3WorldId physics);
    ~World();

    uint8_t get(int x, int y, int z) const;  // Stone where nothing is made yet: never fall into the void
    void set(int x, int y, int z, uint8_t block);
    bool solid(int x, int y, int z) const { return blockInfo(get(x, y, z)).solid; }
    bool loaded(int x, int z) const;
    bool hasCollider(int x, int z) const;
    int surfaceHeight(int x, int z) const;   // height of the generated ground (ignores edits)

    // Makes, meshes and drops chunks around the player, a few per frame so the game never stalls.
    void update(Vector3 player, Vector3 forward);
    void prepareAround(Vector3 player);  // everything near the start, before the first frame
    void draw(Camera3D camera, Vector3 forward);
    void drawTranslucent(Camera3D camera, Vector3 forward);
    bool raycast(Vector3 origin, Vector3 direction, float reach, RayHit& hit) const;

    Texture2D atlas{};
    Shader shader{};
    Material material{};
    size_t chunkCount() const { return chunks_.size(); }

private:
    Chunk* find(int cx, int cz) const;
    Chunk& generate(int cx, int cz);
    void buildMesh(Chunk& chunk);
    void buildCollider(Chunk& chunk);
    void dropMesh(Chunk& chunk);
    void dropCollider(Chunk& chunk);
    bool visible(const Chunk& chunk, Vector3 eye, Vector3 forward) const;

    b3WorldId physics_;
    std::unordered_map<int64_t, std::unique_ptr<Chunk>> chunks_;
};

// ---------------------------------------------------------------------------------------------
// Player: a 0.6 x 1.8 m box moved through the blocks one axis at a time, like block games do.

struct PlayerInput {
    Vector2 move{};        // x: right, y: forward, length up to 1
    bool sprint = false;
    bool jumpHeld = false, jumpPressed = false;
    bool sneak = false;
    bool autoJump = false;  // walking into a one-block step jumps it (touch controls)
};

struct Player {
    Vector3 position{};    // centre of the feet
    Vector3 velocity{};
    float yaw = 0.0f, pitch = -0.2f;
    bool onGround = false, flying = false, inWater = false, sneaking = false;
    double lastJumpPress = -1.0;
    b3BodyId pusher = b3_nullBodyId;  // kinematic box that shoves things out of the way

    static constexpr float kHalfWidth = 0.3f, kHeight = 1.8f, kSneakHeight = 1.5f;
    float height() const { return sneaking && !flying ? kSneakHeight : kHeight; }
    float eyeHeight() const { return sneaking && !flying ? 1.27f : 1.62f; }
    Vector3 eye() const { return {position.x, position.y + eyeHeight(), position.z}; }
    Vector3 forward() const;  // horizontal
    Vector3 look() const;     // where the camera points

    void create(b3WorldId physics, Vector3 at);
    void update(World& world, b3WorldId physics, const PlayerInput& input, float dt, double time);
    bool overlapsBlock(int x, int y, int z) const;
};

// ---------------------------------------------------------------------------------------------
// Things to throw, and the bits of broken blocks.

enum class Item { Ball, Crate, Barrel, BeachBall, Plank, Bomb, Domino, SteelBall, Cone, Tyre, Count };
const char* itemName(Item item);
Color itemColor(Item item);

struct Thing {
    b3BodyId body;
    Item item;
    Color color;
    float volume;    // m^3, for floating
    float bottom;    // centre to lowest point, roughly
    float fuse;      // seconds before a bomb goes off
    bool wet;
    uint8_t block;   // for debris: the block it came from
    float life;      // for debris: seconds left (< 0: forever)
};

class Things {
public:
    explicit Things(b3WorldId physics);
    ~Things();
    void spawn(Item item, Vector3 at, Vector3 velocity, float yaw);
    void debris(Vector3 blockCentre, uint8_t block, int count);
    // Floating, fuses, explosions (which also break blocks), sleeping far from colliders.
    void update(World& world, float dt);
    void draw(const World& world, float time) const;
    void clear();
    bool push(Vector3 origin, Vector3 direction, float reach);  // punch with the bare hand
    int count() const { return static_cast<int>(things_.size()); }

    struct Blast { Vector3 at; float age; };
    std::vector<Blast> blasts;

private:
    b3WorldId physics_;
    std::vector<Thing> things_;
    b3HullData* barrel_ = nullptr;
    b3HullData* cone_ = nullptr;
    b3HullData* tyre_ = nullptr;
    int serial_ = 0;
};

// Lit triangles for things and the hand (things.cpp).
void drawBox(Vector3 half, Color color, Vector3 light);
void drawBlockCube(const World& world, uint8_t block, float half, Vector3 light);
void drawItem(Item item, Color color, float fuse, float time, Vector3 light);
extern const Vector3 kSun;

// ---------------------------------------------------------------------------------------------
// Hotbar and inventory

struct Slot {
    enum Kind : uint8_t { Empty, BlockSlot, ItemSlot } kind = Empty;
    int id = 0;
};

constexpr int kHotbarSlots = 9;

// What the player did with the screen, keys and mouse this frame.
struct Actions {
    PlayerInput input;
    bool use = false;            // place the block or throw the item in hand; punch with an empty hand
    Vector2 usePoint{};          // where on the screen (tap or click)
    bool breakBlock = false;
    Vector2 breakPoint{};
    bool pick = false;           // copy the block under the pointer into the hand (middle click)
    Vector2 pickPoint{};
    bool clearThings = false;
    bool hasHover = false;       // a pointer over the world, for the block outline
    Vector2 hover{};
};

class Ui {
public:
    Ui();
    Actions update(Player& player, float dt, double time);
    void draw(const World& world, const Player& player, const Things& things, double time) const;
    void drawHand(const World& world, const Player& player, float swing) const;
    Slot& held() { return hotbar_[selected_]; }
    bool inventoryOpen() const { return inventoryOpen_; }
    void pick(uint8_t block, double time);              // put the block in the hand (middle click)
    void say(const std::string& text, double time);     // a short note above the hotbar

private:
    struct Layout;
    Layout layout() const;

    // A finger on the screen (or the mouse), and what it landed on.
    struct Finger {
        enum Role { World, Pad, Jump, Sneak, Button } role = World;
        int id = 0;
        Vector2 start{}, last{};
        double pressedAt = 0, lastBreak = 0;
        bool moved = false, alive = false, breaking = false;
    };

    Slot hotbar_[kHotbarSlots];
    int selected_ = 0;
    bool inventoryOpen_ = false;
    bool sneakToggle_ = false;
    std::vector<Slot> catalogue_;
    std::vector<Finger> fingers_;
    Vector2 pad_{};
    bool jumpHeld_ = false, sneakHeld_ = false;
    std::string toast_;
    double toastUntil_ = 0;
};

void drawSlotIcon(const World& world, Slot slot, Vector2 centre, float size);
