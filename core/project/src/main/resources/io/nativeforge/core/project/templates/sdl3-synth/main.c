#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <math.h>

/*
 * A multi-touch synthesizer with SDL3: every finger plays a note of a pentatonic scale
 * (left to right) with a brighter sound towards the top of the screen. The sound is
 * computed sample by sample in an audio callback and played through SDL's audio stream.
 * No sound files and no extra libraries.
 */

#define SAMPLE_RATE 48000
#define MAX_VOICES 10
#define KEYS 10

typedef struct {
    bool active;         /* a finger is holding this voice */
    SDL_FingerID finger;
    float frequency;
    float brightness;    /* 0 = soft sine, 1 = bright, many harmonics */
    float phase;
    float level;         /* current loudness, follows the target for click-free starts and stops */
    float target;
} voice_t;

static voice_t voices[MAX_VOICES];
static const int SCALE[KEYS] = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21}; /* semitones above C4 */

static float key_frequency(int key)
{
    return 261.63f * powf(2.0f, SCALE[key] / 12.0f);
}

/* Called by SDL on its audio thread whenever the device needs more samples. */
static void SDLCALL generate_audio(void *userdata, SDL_AudioStream *stream, int additional_amount, int total_amount)
{
    (void)userdata;
    (void)total_amount;
    float buffer[512];
    int samples = additional_amount / (int)sizeof(float);
    while (samples > 0) {
        int count = samples < 512 ? samples : 512;
        for (int i = 0; i < count; i++) {
            float mix = 0.0f;
            for (int v = 0; v < MAX_VOICES; v++) {
                voice_t *voice = &voices[v];
                voice->level += (voice->target - voice->level) * 0.002f;
                if (voice->level < 0.0001f && voice->target == 0.0f) continue;
                voice->phase += voice->frequency / SAMPLE_RATE;
                if (voice->phase >= 1.0f) voice->phase -= 1.0f;
                float x = voice->phase * 2.0f * SDL_PI_F;
                /* Additive synthesis: odd harmonics fade in with the brightness. */
                float sample = sinf(x) + voice->brightness * (sinf(3 * x) / 3 + sinf(5 * x) / 5 + sinf(7 * x) / 7);
                mix += sample * voice->level;
            }
            buffer[i] = tanhf(mix * 0.35f); /* soft limiter: many fingers never clip */
        }
        SDL_PutAudioStreamData(stream, buffer, count * (int)sizeof(float));
        samples -= count;
    }
}

static voice_t *voice_for(SDL_FingerID finger, bool allocate)
{
    for (int v = 0; v < MAX_VOICES; v++) if (voices[v].active && voices[v].finger == finger) return &voices[v];
    if (!allocate) return NULL;
    for (int v = 0; v < MAX_VOICES; v++) {
        if (!voices[v].active && voices[v].level < 0.001f) {
            voices[v].active = true;
            voices[v].finger = finger;
            return &voices[v];
        }
    }
    return NULL;
}

static void play(SDL_AudioStream *stream, SDL_FingerID finger, float x, float y, bool down)
{
    SDL_LockAudioStream(stream); /* the audio thread reads the voices too */
    voice_t *voice = voice_for(finger, down);
    if (voice != NULL) {
        int key = (int)(x * KEYS);
        if (key < 0) key = 0;
        if (key >= KEYS) key = KEYS - 1;
        voice->frequency = key_frequency(key);
        voice->brightness = 1.0f - y;
        voice->target = 0.5f;
    }
    SDL_UnlockAudioStream(stream);
}

static void release(SDL_AudioStream *stream, SDL_FingerID finger)
{
    SDL_LockAudioStream(stream);
    voice_t *voice = voice_for(finger, false);
    if (voice != NULL) {
        voice->target = 0.0f;
        voice->active = false;
    }
    SDL_UnlockAudioStream(stream);
}

int main(int argc, char *argv[])
{
    (void)argc;
    (void)argv;
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO)) {
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

    const SDL_AudioSpec spec = {SDL_AUDIO_F32, 1, SAMPLE_RATE};
    SDL_AudioStream *stream = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, generate_audio, NULL);
    if (stream == NULL) {
        SDL_Log("Could not open the audio device: %s", SDL_GetError());
        return 1;
    }
    SDL_ResumeAudioStreamDevice(stream);

    bool running = true;
    while (running) {
        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            switch (event.type) {
                case SDL_EVENT_QUIT:
                    running = false;
                    break;
                case SDL_EVENT_FINGER_DOWN:
                case SDL_EVENT_FINGER_MOTION:
                    play(stream, event.tfinger.fingerID, event.tfinger.x, event.tfinger.y, true);
                    break;
                case SDL_EVENT_FINGER_UP:
                case SDL_EVENT_FINGER_CANCELED:
                    release(stream, event.tfinger.fingerID);
                    break;
                case SDL_EVENT_MOUSE_BUTTON_DOWN: /* a real mouse; touches arrive as fingers */
                    if (event.button.which != SDL_TOUCH_MOUSEID) {
                        int w, h;
                        SDL_GetWindowSize(window, &w, &h);
                        play(stream, (SDL_FingerID)-1, event.button.x / w, event.button.y / h, true);
                    }
                    break;
                case SDL_EVENT_MOUSE_BUTTON_UP:
                    if (event.button.which != SDL_TOUCH_MOUSEID) release(stream, (SDL_FingerID)-1);
                    break;
                default:
                    break;
            }
        }

        int width = 0, height = 0;
        SDL_GetRenderOutputSize(renderer, &width, &height);
        SDL_SetRenderDrawColor(renderer, 16, 18, 26, 255);
        SDL_RenderClear(renderer);
        /* Keys: a column per note, lit while a finger plays it. */
        SDL_LockAudioStream(stream);
        for (int key = 0; key < KEYS; key++) {
            float lit = 0.0f;
            for (int v = 0; v < MAX_VOICES; v++) {
                if (voices[v].frequency == key_frequency(key) && voices[v].level > lit) lit = voices[v].level * 2.0f;
            }
            Uint8 base = (Uint8)(40 + key * 8);
            SDL_SetRenderDrawColor(renderer, (Uint8)(base + lit * 150), (Uint8)(60 + lit * 120), (Uint8)(120 + lit * 100), 255);
            SDL_FRect rect = {key * (float)width / KEYS + 3, 3, (float)width / KEYS - 6, (float)height - 6};
            SDL_RenderFillRect(renderer, &rect);
        }
        SDL_UnlockAudioStream(stream);
        /* SDL's built-in debug font is 8x8 pixels: scale it so that the longest line fits the
           width, and is not larger than 1/45 of the screen's short side per character. */
        static const char *const lines[] = {"Touch synth", "Play with up to 10 fingers", "Higher = brighter sound"};
        const float longest = (float)SDL_strlen(lines[1]) * 8.0f + 16.0f;
        const float short_side = (float)SDL_min(width, height);
        const float text_scale = SDL_max(1.0f, SDL_min((float)width / longest, short_side / 45.0f / 8.0f));
        /* A dark band behind the text keeps it readable over lit keys. */
        SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
        SDL_SetRenderDrawColor(renderer, 0, 0, 0, 140);
        SDL_RenderFillRect(renderer, &(SDL_FRect){0, 0, (float)width, 46 * text_scale});
        SDL_SetRenderScale(renderer, text_scale, text_scale);
        SDL_SetRenderDrawColor(renderer, 255, 255, 255, 255);
        for (int i = 0; i < 3; i++) SDL_RenderDebugText(renderer, 8, 8 + i * 12.0f, lines[i]);
        SDL_SetRenderScale(renderer, 1.0f, 1.0f);
        SDL_RenderPresent(renderer);
    }

    SDL_DestroyAudioStream(stream);
    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 0;
}
