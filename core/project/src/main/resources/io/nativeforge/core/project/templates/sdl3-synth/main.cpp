#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

/*
 * A multi-touch synthesizer with SDL3 in C++: every finger plays a note of a pentatonic scale
 * (left to right) with a brighter sound towards the top of the screen. The sound is computed
 * sample by sample in an audio callback and played through SDL's audio stream.
 * No sound files and no extra libraries.
 *
 * SDL is a C library; its headers declare everything extern "C", so C++ calls it directly.
 * The audio callback runs on another thread, so the voices are only touched while the
 * stream is locked (see AudioLock).
 */

namespace {

constexpr int SAMPLE_RATE = 48000;
constexpr int KEYS = 10;
constexpr std::array<int, KEYS> SCALE = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21}; // semitones above C4

float keyFrequency(int key)
{
    return 261.63f * std::pow(2.0f, SCALE[key] / 12.0f);
}

struct Voice {
    bool active = false;     // a finger is holding this voice
    SDL_FingerID finger = 0;
    int key = -1;
    float frequency = 0.0f;
    float brightness = 0.0f; // 0 = soft sine, 1 = bright, many harmonics
    float phase = 0.0f;
    float level = 0.0f;      // current loudness, follows the target for click-free starts and stops
    float target = 0.0f;

    float nextSample()
    {
        level += (target - level) * 0.002f;
        if (level < 0.0001f && target == 0.0f) return 0.0f;
        phase += frequency / SAMPLE_RATE;
        if (phase >= 1.0f) phase -= 1.0f;
        const float x = phase * 2.0f * SDL_PI_F;
        // Additive synthesis: odd harmonics fade in with the brightness.
        const float sample = std::sin(x) + brightness * (std::sin(3 * x) / 3 + std::sin(5 * x) / 5 + std::sin(7 * x) / 7);
        return sample * level;
    }
};

/* Locks an audio stream for as long as it exists, like std::lock_guard for a mutex. */
class AudioLock {
public:
    explicit AudioLock(SDL_AudioStream *stream) : stream_(stream) { SDL_LockAudioStream(stream_); }
    ~AudioLock() { SDL_UnlockAudioStream(stream_); }
    AudioLock(const AudioLock &) = delete;
    AudioLock &operator=(const AudioLock &) = delete;

private:
    SDL_AudioStream *stream_;
};

class Synth {
public:
    /* Called by SDL on its audio thread whenever the device needs more samples. */
    static void SDLCALL generate(void *userdata, SDL_AudioStream *stream, int additional_amount, int)
    {
        auto &synth = *static_cast<Synth *>(userdata);
        std::array<float, 512> buffer;
        int samples = additional_amount / static_cast<int>(sizeof(float));
        while (samples > 0) {
            const int count = std::min(samples, static_cast<int>(buffer.size()));
            for (int i = 0; i < count; i++) {
                float mix = 0.0f;
                for (Voice &voice : synth.voices_) mix += voice.nextSample();
                buffer[i] = std::tanh(mix * 0.35f); // soft limiter: many fingers never clip
            }
            SDL_PutAudioStreamData(stream, buffer.data(), count * static_cast<int>(sizeof(float)));
            samples -= count;
        }
    }

    bool open()
    {
        const SDL_AudioSpec spec = {SDL_AUDIO_F32, 1, SAMPLE_RATE};
        stream_ = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, generate, this);
        if (stream_ == nullptr) return false;
        SDL_ResumeAudioStreamDevice(stream_);
        return true;
    }

    ~Synth() { SDL_DestroyAudioStream(stream_); } // stops the audio thread before the voices go away

    void play(SDL_FingerID finger, float x, float y)
    {
        AudioLock lock(stream_);
        Voice *voice = voiceFor(finger, true);
        if (voice == nullptr) return;
        voice->key = std::clamp(static_cast<int>(x * KEYS), 0, KEYS - 1);
        voice->frequency = keyFrequency(voice->key);
        voice->brightness = 1.0f - y;
        voice->target = 0.5f;
    }

    void release(SDL_FingerID finger)
    {
        AudioLock lock(stream_);
        if (Voice *voice = voiceFor(finger, false)) {
            voice->target = 0.0f;
            voice->active = false;
        }
    }

    /* How loud each key sounds right now, from 0 to 1, for drawing. */
    std::array<float, KEYS> keyLevels()
    {
        std::array<float, KEYS> levels{};
        AudioLock lock(stream_);
        for (const Voice &voice : voices_) {
            if (voice.key >= 0) levels[voice.key] = std::max(levels[voice.key], std::min(1.0f, voice.level * 2.0f));
        }
        return levels;
    }

private:
    Voice *voiceFor(SDL_FingerID finger, bool allocate)
    {
        for (Voice &voice : voices_) if (voice.active && voice.finger == finger) return &voice;
        if (!allocate) return nullptr;
        for (Voice &voice : voices_) {
            if (!voice.active && voice.level < 0.001f) {
                voice.active = true;
                voice.finger = finger;
                return &voice;
            }
        }
        return nullptr;
    }

    std::array<Voice, 10> voices_{};
    SDL_AudioStream *stream_ = nullptr;
};

void draw(SDL_Renderer *renderer, const std::array<float, KEYS> &levels)
{
    int width = 0, height = 0;
    SDL_GetRenderOutputSize(renderer, &width, &height);
    SDL_SetRenderDrawColor(renderer, 16, 18, 26, 255);
    SDL_RenderClear(renderer);
    // Keys: a column per note, lit while a finger plays it.
    const float keyWidth = static_cast<float>(width) / KEYS;
    for (int key = 0; key < KEYS; key++) {
        const float lit = levels[key];
        const auto base = static_cast<float>(40 + key * 8);
        SDL_SetRenderDrawColor(renderer, static_cast<Uint8>(base + lit * 150), static_cast<Uint8>(60 + lit * 120),
                               static_cast<Uint8>(120 + lit * 100), 255);
        const SDL_FRect rect = {key * keyWidth + 3, 3, keyWidth - 6, static_cast<float>(height) - 6};
        SDL_RenderFillRect(renderer, &rect);
    }
    // SDL's built-in debug font is 8x8 pixels: scale it so that the longest line fits the
    // width, and is not larger than 1/45 of the screen's short side per character.
    static constexpr std::array<const char *, 3> lines = {"Touch synth", "Play with up to 10 fingers", "Higher = brighter sound"};
    const float longest = static_cast<float>(std::strlen(lines[1])) * 8.0f + 16.0f;
    const auto shortSide = static_cast<float>(std::min(width, height));
    const float textScale = std::max(1.0f, std::min(static_cast<float>(width) / longest, shortSide / 45.0f / 8.0f));
    // A dark band behind the text keeps it readable over lit keys.
    SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
    SDL_SetRenderDrawColor(renderer, 0, 0, 0, 140);
    const SDL_FRect band = {0, 0, static_cast<float>(width), 46 * textScale};
    SDL_RenderFillRect(renderer, &band);
    SDL_SetRenderScale(renderer, textScale, textScale);
    SDL_SetRenderDrawColor(renderer, 255, 255, 255, 255);
    for (std::size_t i = 0; i < lines.size(); i++) SDL_RenderDebugText(renderer, 8, 8 + i * 12.0f, lines[i]);
    SDL_SetRenderScale(renderer, 1.0f, 1.0f);
    SDL_RenderPresent(renderer);
}

} // namespace

int main(int, char *[])
{
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO)) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }
    SDL_Window *window = nullptr;
    SDL_Renderer *renderer = nullptr;
    if (!SDL_CreateWindowAndRenderer("@PROJECT_NAME@", 0, 0, SDL_WINDOW_FULLSCREEN, &window, &renderer)) {
        SDL_Log("Could not create the window: %s", SDL_GetError());
        return 1;
    }
    SDL_SetRenderVSync(renderer, 1);

    {
        Synth synth;
        if (!synth.open()) {
            SDL_Log("Could not open the audio device: %s", SDL_GetError());
            return 1;
        }
        constexpr SDL_FingerID MOUSE_FINGER = ~SDL_FingerID{0};
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
                        synth.play(event.tfinger.fingerID, event.tfinger.x, event.tfinger.y);
                        break;
                    case SDL_EVENT_FINGER_UP:
                    case SDL_EVENT_FINGER_CANCELED:
                        synth.release(event.tfinger.fingerID);
                        break;
                    case SDL_EVENT_MOUSE_BUTTON_DOWN: // a real mouse; touches arrive as fingers
                        if (event.button.which != SDL_TOUCH_MOUSEID) {
                            int w = 1, h = 1;
                            SDL_GetWindowSize(window, &w, &h);
                            synth.play(MOUSE_FINGER, event.button.x / w, event.button.y / h);
                        }
                        break;
                    case SDL_EVENT_MOUSE_BUTTON_UP:
                        if (event.button.which != SDL_TOUCH_MOUSEID) synth.release(MOUSE_FINGER);
                        break;
                    default:
                        break;
                }
            }
            draw(renderer, synth.keyLevels());
        }
    } // the synth closes its audio stream here

    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 0;
}
