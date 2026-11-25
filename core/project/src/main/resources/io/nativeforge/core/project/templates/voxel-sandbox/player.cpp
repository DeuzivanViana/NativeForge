// The player: a box that walks, jumps, swims, flies and sneaks through the blocks. It does not use
// Box3D to move, because block games feel right with plain box-against-grid collision: the box moves
// one axis at a time and stops flat against the first solid block. A kinematic Box3D box follows it
// so the things you throw get pushed out of the way, and the player can stand on them.
#include "game.hpp"

#include <algorithm>
#include <cmath>

namespace {
// Speeds in blocks per second, accelerations in blocks per second squared.
constexpr float kGravity = 32.0f;
constexpr float kAirDrag = 0.4f;      // per second, gives a top falling speed of about 80
constexpr float kJumpSpeed = 9.4f;    // a bit more than one block high
constexpr float kWalk = 4.3f, kSprint = 5.6f, kSneak = 1.3f, kSwim = 2.2f;
constexpr float kFly = 10.9f, kFlySprint = 21.6f, kFlyVertical = 7.5f;
constexpr float kGroundGrip = 16.0f;  // how fast the speed follows the stick, per second
constexpr float kAirGrip = 2.0f;      // little steering in the air: momentum carries a jump
constexpr float kSkin = 0.001f;       // gap kept between the box and the blocks
constexpr double kDoubleTap = 0.3;    // two jump presses this close toggle flying

struct Box {
    float lo[3], hi[3];
};

Box boxAt(Vector3 p, float height) {
    const float h = Player::kHalfWidth;
    return {{p.x - h, p.y, p.z - h}, {p.x + h, p.y + height, p.z + h}};
}

int cell(float v) { return static_cast<int>(std::floor(v)); }

// True if any solid block overlaps the box (the skin is ignored, so touching is not overlapping).
bool overlaps(const World& world, const Box& b) {
    for (int y = cell(b.lo[1] + kSkin); y <= cell(b.hi[1] - kSkin); ++y)
        for (int z = cell(b.lo[2] + kSkin); z <= cell(b.hi[2] - kSkin); ++z)
            for (int x = cell(b.lo[0] + kSkin); x <= cell(b.hi[0] - kSkin); ++x)
                if (world.solid(x, y, z)) return true;
    return false;
}

// How far the box can move along one axis before it touches a solid block. Blocks the box is
// already inside are skipped, so a player stuck in a block can always walk out.
float clip(const World& world, const Box& b, int axis, float delta) {
    if (delta == 0.0f) return 0.0f;
    const int u = (axis + 1) % 3, v = (axis + 2) % 3;
    auto blocked = [&](int i) {
        for (int a = cell(b.lo[u] + kSkin); a <= cell(b.hi[u] - kSkin); ++a) {
            for (int c = cell(b.lo[v] + kSkin); c <= cell(b.hi[v] - kSkin); ++c) {
                int p[3];
                p[axis] = i;
                p[u] = a;
                p[v] = c;
                if (world.solid(p[0], p[1], p[2])) return true;
            }
        }
        return false;
    };
    if (delta > 0) {
        for (int i = static_cast<int>(std::ceil(b.hi[axis] - kSkin)); i < b.hi[axis] + delta; ++i)
            if (blocked(i)) return std::max(0.0f, i - b.hi[axis] - kSkin);
    } else {
        for (int i = cell(b.lo[axis] + kSkin) - 1; i + 1 > b.lo[axis] + delta; --i)
            if (blocked(i)) return std::min(0.0f, i + 1 - b.lo[axis] + kSkin);
    }
    return delta;
}

// Something solid under the footprint, at most 0.6 below the feet: sneaking never walks off it.
bool groundUnder(const World& world, Vector3 p) {
    const Box b = boxAt({p.x, p.y - 0.6f, p.z}, 0.6f - kSkin * 2);
    return overlaps(world, b);
}

bool waterAt(const World& world, Vector3 p) {
    return world.get(cell(p.x), cell(p.y), cell(p.z)) == Water;
}

// The tops of the things under the player, collected once a frame from Box3D.
struct ThingTops {
    std::vector<b3AABB> boxes;
};

bool collectThing(b3ShapeId shape, void* context) {
    static_cast<ThingTops*>(context)->boxes.push_back(b3Shape_GetAABB(shape));
    return true;
}
}  // namespace

Vector3 Player::forward() const { return {std::sin(yaw), 0.0f, std::cos(yaw)}; }

Vector3 Player::look() const {
    return {std::sin(yaw) * std::cos(pitch), std::sin(pitch), std::cos(yaw) * std::cos(pitch)};
}

bool Player::overlapsBlock(int x, int y, int z) const {
    const Box b = boxAt(position, height());
    return x + 1 > b.lo[0] + kSkin && x < b.hi[0] - kSkin && y + 1 > b.lo[1] + kSkin && y < b.hi[1] - kSkin &&
           z + 1 > b.lo[2] + kSkin && z < b.hi[2] - kSkin;
}

void Player::create(b3WorldId physics, Vector3 at) {
    position = at;
    // From a little above the feet to the head: things on the ground roll under the player's feet
    // and get pushed, but a thing the player stands on is left alone.
    b3BodyDef def = b3DefaultBodyDef();
    def.type = b3_kinematicBody;
    def.position = {at.x, at.y + 1.025f, at.z};
    def.enableSleep = false;
    pusher = b3CreateBody(physics, &def);
    const b3BoxHull hull = b3MakeBoxHull(kHalfWidth, 0.775f, kHalfWidth);
    b3ShapeDef shape = b3DefaultShapeDef();
    shape.filter.categoryBits = kPlayerBit;
    shape.filter.maskBits = kThingBit;
    shape.baseMaterial.friction = 0.2f;
    b3CreateHullShape(pusher, &shape, &hull.base);
}

void Player::update(World& world, b3WorldId physics, const PlayerInput& input, float dt, double time) {
    // Double tap jump: start or stop flying.
    if (input.jumpPressed) {
        if (time - lastJumpPress < kDoubleTap) {
            flying = !flying;
            velocity.y = 0.0f;
            lastJumpPress = -1.0;
        } else {
            lastJumpPress = time;
        }
    }
    // Sneaking makes the box lower; standing up again needs room above the head.
    const bool wantSneak = input.sneak && !flying;
    if (wantSneak) sneaking = true;
    else if (sneaking && !overlaps(world, boxAt(position, kHeight))) sneaking = false;

    inWater = waterAt(world, {position.x, position.y + 0.4f, position.z});

    // Where the stick points, flat on the ground.
    const Vector3 f = forward();
    const Vector3 right{-f.z, 0.0f, f.x};
    Vector3 wish{f.x * input.move.y + right.x * input.move.x, 0.0f, f.z * input.move.y + right.z * input.move.x};
    float speed = kWalk;
    if (flying) speed = input.sprint ? kFlySprint : kFly;
    else if (inWater) speed = kSwim;
    else if (sneaking) speed = kSneak;
    else if (input.sprint && input.move.y > 0.3f) speed = kSprint;
    wish = Vector3Scale(wish, speed);

    // Things under the feet: Box3D knows where they are.
    ThingTops tops;
    {
        const float below = std::max(1.0f, -velocity.y * dt + 0.5f);
        b3AABB area{{position.x - kHalfWidth, position.y - below, position.z - kHalfWidth},
                    {position.x + kHalfWidth, position.y + 0.3f, position.z + kHalfWidth}};
        b3QueryFilter filter = b3DefaultQueryFilter();
        filter.categoryBits = kPlayerBit;
        filter.maskBits = kThingBit;
        b3World_OverlapAABB(physics, area, filter, collectThing, &tops);
    }

    // Small steps, so even a fast fall never skips a block.
    const int steps = std::max(1, static_cast<int>(std::ceil(dt * 120.0f)));
    const float h = dt / steps;
    bool hitWall = false;
    for (int step = 0; step < steps; ++step) {
        // Horizontal speed follows the stick: quickly on the ground, slowly in the air.
        const float grip = flying || inWater ? 6.0f : onGround ? kGroundGrip : kAirGrip;
        const float k = 1.0f - std::exp(-grip * h);
        velocity.x += (wish.x - velocity.x) * k;
        velocity.z += (wish.z - velocity.z) * k;

        if (flying) {
            const float target = (input.jumpHeld ? kFlyVertical : 0.0f) - (input.sneak ? kFlyVertical : 0.0f);
            velocity.y += (target - velocity.y) * (1.0f - std::exp(-10.0f * h));
        } else if (inWater) {
            velocity.y -= kGravity * 0.25f * h;
            velocity.y *= std::exp(-3.0f * h);
            if (input.jumpHeld) velocity.y = std::min(velocity.y + 24.0f * h, 3.5f);
            velocity.y = std::max(velocity.y, -3.0f);
        } else {
            if (onGround && input.jumpHeld) velocity.y = kJumpSpeed;  // holding jump keeps hopping
            velocity.y -= kGravity * h;
            velocity.y *= std::exp(-kAirDrag * h);
        }

        // Vertical first, then the two horizontal axes.
        const float height = this->height();
        const float dy = velocity.y * h;
        float moved = clip(world, boxAt(position, height), 1, dy);
        // Landing on a thing: only from above, onto the top of its box.
        if (dy <= 0.0f) {
            for (const b3AABB& t : tops.boxes) {
                const bool under = t.lowerBound.x < position.x + kHalfWidth && t.upperBound.x > position.x - kHalfWidth &&
                                   t.lowerBound.z < position.z + kHalfWidth && t.upperBound.z > position.z - kHalfWidth;
                const float top = t.upperBound.y;
                if (under && top <= position.y + 0.02f && top > position.y + moved - 0.001f) moved = top - position.y;
            }
        }
        position.y += moved;
        const bool landed = dy < 0.0f && moved > dy + 1e-5f;
        if (moved != dy) velocity.y = 0.0f;
        onGround = landed || (dy == 0.0f && onGround);
        if (landed && flying) flying = false;  // touching the ground ends a flight

        for (int axis : {0, 2}) {
            float delta = (axis == 0 ? velocity.x : velocity.z) * h;
            // Sneaking on the ground never steps off an edge: back off until there is ground.
            if (sneaking && onGround && !flying) {
                while (delta != 0.0f) {
                    Vector3 next = position;
                    (axis == 0 ? next.x : next.z) += delta;
                    if (groundUnder(world, next)) break;
                    delta = std::fabs(delta) < 0.01f ? 0.0f : delta * 0.5f;
                }
            }
            const float allowed = clip(world, boxAt(position, height), axis, delta);
            if (allowed != delta) {
                hitWall = true;
                (axis == 0 ? velocity.x : velocity.z) = 0.0f;
            }
            (axis == 0 ? position.x : position.z) += allowed;
        }
    }
    // Still on the ground? (Walking off an edge starts a fall.)
    if (onGround && !flying) {
        bool support = overlaps(world, boxAt({position.x, position.y - 0.01f, position.z}, 0.01f - kSkin * 2) );
        for (const b3AABB& t : tops.boxes) {
            support |= t.lowerBound.x < position.x + kHalfWidth && t.upperBound.x > position.x - kHalfWidth &&
                       t.lowerBound.z < position.z + kHalfWidth && t.upperBound.z > position.z - kHalfWidth &&
                       std::fabs(t.upperBound.y - position.y) < 0.03f;
        }
        onGround = support;
    }

    // Touch controls: walking into a one-block step jumps it.
    if (input.autoJump && hitWall && onGround && !flying && !sneaking && Vector3Length(wish) > 1.0f) {
        const Vector3 ahead = Vector3Add(position, Vector3Scale(Vector3Normalize(wish), 0.35f));
        const Box up = boxAt({position.x, position.y + 1.05f, position.z}, kHeight);
        const Box upAhead = boxAt({ahead.x, position.y + 1.05f, ahead.z}, kHeight);
        if (!overlaps(world, up) && !overlaps(world, upAhead)) velocity.y = kJumpSpeed;
    }
    // Swimming into a bank: a hop gets the player out of the water.
    if (inWater && hitWall && input.jumpHeld) velocity.y = std::max(velocity.y, 5.5f);

    // The pusher follows; Box3D gives it the velocity to get there in this step.
    const b3WorldTransform target{{position.x, position.y + 0.25f + (height() - 0.25f) / 2, position.z}, b3Quat_identity};
    b3Body_SetTargetTransform(pusher, target, dt, true);
}
