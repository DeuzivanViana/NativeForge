#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

/*
 * A snake game with SDL3's 2D renderer: swipe (or use the arrow keys) to steer, eat the red
 * squares to grow, and don't run into the walls or yourself. The best score is saved in the
 * app's private folder.
 *
 * The board is laid out from the screen size: about 16 cells across the short side, and the
 * text scales with the screen, so it looks the same on small and large phones and in
 * landscape. This file is written in the common part of C and C++, so it builds as either.
 */

#define MAX_CELLS 2048

typedef struct {
    int x, y;
} Cell;

typedef struct {
    /* Layout, in pixels, recomputed when the window size changes. */
    float unit;       /* 1/360 of the short side: the size of everything else */
    float cell;
    float left, top;  /* the board's top-left corner */
    float hud_height; /* the score line above the board */
    int cols, rows;

    Cell body[MAX_CELLS]; /* body[0] is the head */
    int length;
    int dx, dy;           /* current direction */
    int next_dx, next_dy; /* direction for the next step, from the last swipe */
    Cell food;
    int score, best;
    bool started, over;
    Uint64 last_step;

    /* Swipe tracking. */
    bool swiping;
    float swipe_x, swipe_y;
} Game;

static char *best_path(void)
{
    char *dir = SDL_GetPrefPath("nativeforge", "@PROJECT_NAME@");
    char *path = NULL;
    if (dir != NULL) SDL_asprintf(&path, "%sbest.txt", dir);
    SDL_free(dir);
    return path;
}

static void load_best(Game *game)
{
    char *path = best_path();
    if (path == NULL) return;
    size_t size = 0;
    char *text = (char *)SDL_LoadFile(path, &size);
    if (text != NULL) game->best = SDL_atoi(text);
    SDL_free(text);
    SDL_free(path);
}

static void save_best(const Game *game)
{
    char *path = best_path();
    if (path == NULL) return;
    char text[16];
    int length = SDL_snprintf(text, sizeof text, "%d", game->best);
    SDL_SaveFile(path, text, (size_t)length);
    SDL_free(path);
}

static bool occupied(const Game *game, int x, int y)
{
    for (int i = 0; i < game->length; i++) {
        if (game->body[i].x == x && game->body[i].y == y) return true;
    }
    return false;
}

static void place_food(Game *game)
{
    do {
        game->food.x = SDL_rand(game->cols);
        game->food.y = SDL_rand(game->rows);
    } while (occupied(game, game->food.x, game->food.y));
}

/* Fits the board to a window of width x height pixels. */
static void layout(Game *game, int width, int height)
{
    float short_side = (float)SDL_min(width, height);
    game->unit = short_side / 360.0f;
    game->hud_height = 40.0f * game->unit;
    float margin = 8.0f * game->unit;
    game->cell = SDL_floorf((short_side - 2 * margin) / 16.0f);
    game->cols = SDL_max(5, (int)((width - 2 * margin) / game->cell));
    game->rows = SDL_max(5, (int)((height - game->hud_height - 2 * margin) / game->cell));
    game->rows = SDL_min(game->rows, MAX_CELLS / game->cols); /* only matters on very long screens */
    game->left = (width - game->cols * game->cell) / 2;
    game->top = game->hud_height + (height - game->hud_height - game->rows * game->cell) / 2;
}

static void restart(Game *game)
{
    game->length = 3;
    for (int i = 0; i < game->length; i++) {
        game->body[i].x = game->cols / 2 - i;
        game->body[i].y = game->rows / 2;
    }
    game->dx = game->next_dx = 1;
    game->dy = game->next_dy = 0;
    game->score = 0;
    game->over = false;
    game->started = false;
    place_food(game);
}

/* Turns the snake, unless the new direction would reverse it onto itself. */
static void steer(Game *game, int dx, int dy)
{
    if (game->over) return;
    if (dx == -game->dx && dy == -game->dy) return;
    game->next_dx = dx;
    game->next_dy = dy;
    if (!game->started) game->last_step = SDL_GetTicks();
    game->started = true;
}

static void step(Game *game)
{
    game->dx = game->next_dx;
    game->dy = game->next_dy;
    Cell head = game->body[0];
    head.x += game->dx;
    head.y += game->dy;
    bool eats = head.x == game->food.x && head.y == game->food.y;
    /* The tail moves away this step, so the head may enter the cell it leaves. */
    if (!eats) game->length--;
    if (head.x < 0 || head.y < 0 || head.x >= game->cols || head.y >= game->rows || occupied(game, head.x, head.y)) {
        if (!eats) game->length++;
        game->over = true;
        if (game->score > game->best) {
            game->best = game->score;
            save_best(game);
        }
        return;
    }
    SDL_memmove(&game->body[1], &game->body[0], (size_t)game->length * sizeof(Cell));
    game->body[0] = head;
    game->length++;
    if (eats) {
        game->score++;
        if (game->length < game->cols * game->rows) place_food(game);
        else game->over = true; /* the board is full: a perfect game */
    }
}

static void handle_swipe(Game *game, float x, float y)
{
    float distance_x = x - game->swipe_x, distance_y = y - game->swipe_y;
    float threshold = 20.0f * game->unit;
    if (SDL_fabsf(distance_x) < threshold && SDL_fabsf(distance_y) < threshold) return;
    if (SDL_fabsf(distance_x) > SDL_fabsf(distance_y)) steer(game, distance_x > 0 ? 1 : -1, 0);
    else steer(game, 0, distance_y > 0 ? 1 : -1);
    game->swipe_x = x; /* keep tracking, so one long drag can turn several times */
    game->swipe_y = y;
}

/* Draws a line of SDL's 8x8 debug font, centred at y and scaled to fit the width. */
static void draw_centred_text(SDL_Renderer *renderer, const char *text, float width, float y, float wanted_scale)
{
    float text_width = (float)SDL_strlen(text) * 8.0f;
    float scale = SDL_max(1.0f, SDL_min(wanted_scale, (width - 16.0f) / text_width));
    SDL_SetRenderScale(renderer, scale, scale);
    SDL_RenderDebugText(renderer, (width / scale - text_width) / 2, y / scale - 4, text);
    SDL_SetRenderScale(renderer, 1.0f, 1.0f);
}

static void draw(SDL_Renderer *renderer, const Game *game, int width)
{
    SDL_SetRenderDrawColor(renderer, 18, 24, 20, 255);
    SDL_RenderClear(renderer);

    SDL_FRect board;
    board.x = game->left;
    board.y = game->top;
    board.w = game->cols * game->cell;
    board.h = game->rows * game->cell;
    SDL_SetRenderDrawColor(renderer, 30, 42, 34, 255);
    SDL_RenderFillRect(renderer, &board);

    float gap = SDL_max(1.0f, game->cell / 10);
    SDL_FRect rect;
    rect.w = rect.h = game->cell - 2 * gap;
    rect.x = game->left + game->food.x * game->cell + gap;
    rect.y = game->top + game->food.y * game->cell + gap;
    SDL_SetRenderDrawColor(renderer, 230, 70, 60, 255);
    SDL_RenderFillRect(renderer, &rect);

    for (int i = game->length - 1; i >= 0; i--) {
        rect.x = game->left + game->body[i].x * game->cell + gap;
        rect.y = game->top + game->body[i].y * game->cell + gap;
        if (i == 0) SDL_SetRenderDrawColor(renderer, 170, 240, 120, 255);
        else SDL_SetRenderDrawColor(renderer, 90, (Uint8)(200 - SDL_min(i, 100)), 90, 255);
        SDL_RenderFillRect(renderer, &rect);
    }

    char line[64];
    SDL_snprintf(line, sizeof line, "Score %d   Best %d", game->score, game->best);
    SDL_SetRenderDrawColor(renderer, 255, 255, 255, 255);
    draw_centred_text(renderer, line, (float)width, game->hud_height / 2, 2.4f * game->unit);

    const char *message = game->over ? "Game over - tap to play again" : game->started ? NULL : "Swipe to start";
    if (message != NULL) {
        SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
        SDL_SetRenderDrawColor(renderer, 0, 0, 0, 150);
        SDL_FRect band;
        band.x = 0;
        band.w = (float)width;
        band.h = 48 * game->unit;
        float centre = game->top + board.h / 4; /* above the snake, which starts in the middle */
        band.y = centre - band.h / 2;
        SDL_RenderFillRect(renderer, &band);
        SDL_SetRenderDrawColor(renderer, 255, 255, 255, 255);
        draw_centred_text(renderer, message, (float)width, centre, 2.4f * game->unit);
    }
    SDL_RenderPresent(renderer);
}

int main(int argc, char *argv[])
{
    (void)argc;
    (void)argv;
    if (!SDL_Init(SDL_INIT_VIDEO)) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }
    SDL_Window *window = NULL;
    SDL_Renderer *renderer = NULL;
    if (!SDL_CreateWindowAndRenderer("@PROJECT_NAME@", 0, 0, SDL_WINDOW_FULLSCREEN, &window, &renderer)) {
        SDL_Log("Could not create the window: %s", SDL_GetError());
        return 1;
    }
    SDL_SetRenderVSync(renderer, 1);

    SDL_srand(0); /* 0: seed from the clock, for a new food layout every game */
    static Game game; /* static: the body array is too large for some stacks */
    int width = 0, height = 0;
    SDL_GetRenderOutputSize(renderer, &width, &height);
    layout(&game, width, height);
    load_best(&game);
    restart(&game);

    bool running = true;
    while (running) {
        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            switch (event.type) {
                case SDL_EVENT_QUIT:
                    running = false;
                    break;
                case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED: /* rotated: lay out again and start over */
                    SDL_GetRenderOutputSize(renderer, &width, &height);
                    layout(&game, width, height);
                    restart(&game);
                    break;
                case SDL_EVENT_KEY_DOWN:
                    if (event.key.key == SDLK_UP || event.key.key == SDLK_W) steer(&game, 0, -1);
                    else if (event.key.key == SDLK_DOWN || event.key.key == SDLK_S) steer(&game, 0, 1);
                    else if (event.key.key == SDLK_LEFT || event.key.key == SDLK_A) steer(&game, -1, 0);
                    else if (event.key.key == SDLK_RIGHT || event.key.key == SDLK_D) steer(&game, 1, 0);
                    else if (game.over && event.key.key == SDLK_SPACE) restart(&game);
                    else if (event.key.key == SDLK_AC_BACK || event.key.key == SDLK_ESCAPE) running = false;
                    break;
                case SDL_EVENT_FINGER_DOWN:
                    if (game.over) {
                        restart(&game);
                        break;
                    }
                    game.swiping = true;
                    game.swipe_x = event.tfinger.x * width;
                    game.swipe_y = event.tfinger.y * height;
                    break;
                case SDL_EVENT_FINGER_MOTION:
                    if (game.swiping) handle_swipe(&game, event.tfinger.x * width, event.tfinger.y * height);
                    break;
                case SDL_EVENT_FINGER_UP:
                    game.swiping = false;
                    break;
                default:
                    break;
            }
        }

        /* Moves one cell at a time, faster as the snake grows. */
        Uint64 step_ms = (Uint64)SDL_max(70, 170 - game.score * 4);
        Uint64 now = SDL_GetTicks();
        if (game.started && !game.over && now - game.last_step >= step_ms) {
            step(&game);
            game.last_step = now;
        }
        draw(renderer, &game, width);
    }

    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 0;
}
