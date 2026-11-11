#pragma once
#include <array>
#include <cstdint>
#include <vector>
#include <functional>
#include <stdexcept>
#include <chrono>
#include <utility>

namespace eightcb {
constexpr int sample_rate = 12000;
constexpr int slot_samples = 180000;
constexpr int early_window_samples = 162000;
using Payload = std::array<uint8_t, 10>;
using Tones = std::array<uint8_t, 79>;
struct Frame {
    Payload payload;
    float time_seconds;
    float frequency_hz;
    int sync_score;
    float estimated_snr_db; // NaN when a noise floor cannot be measured.
};
enum class WorkStage { preparing, monitoring, searching, decoding, snr, encoding };
using StopRequested = std::function<bool(WorkStage)>;
struct WorkCancelled : std::runtime_error { WorkCancelled() : std::runtime_error("Offline engine request cancelled") {} };
struct WorkExpired : std::runtime_error { WorkExpired() : std::runtime_error("Offline engine deadline exceeded. Try a faster profile.") {} };
struct DecodeSettings { int candidates; int iterations; int deadline_ms; };
DecodeSettings decode_settings(int profile);
struct DecodeResult {
    std::vector<Frame> frames;
    int candidates;
    int processed = 0;
};
// Also used by host checks to exercise the same deadline/cancellation policy.
class WorkBudget {
    std::chrono::steady_clock::time_point deadline;
    StopRequested stop;
public:
    WorkBudget(int milliseconds, StopRequested callback = {}) :
        deadline(std::chrono::steady_clock::now()+std::chrono::milliseconds(milliseconds)), stop(std::move(callback)) {}
    void check(WorkStage stage) const {
        if (stop && stop(stage)) throw WorkCancelled();
        if (std::chrono::steady_clock::now() >= deadline) throw WorkExpired();
    }
};
Tones encode_tones(Payload const& payload);
std::vector<float> encode_slot(Payload const& payload, float frequency_hz, float peak_amplitude = 0.4f, StopRequested const& stop = {});
DecodeResult decode_slot(std::vector<float> const& pcm, int profile = 1, StopRequested const& stop = {});
}
