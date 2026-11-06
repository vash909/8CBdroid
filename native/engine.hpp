#pragma once
#include <array>
#include <cstdint>
#include <vector>

namespace eightcb {
constexpr int sample_rate = 12000;
constexpr int slot_samples = 180000;
using Payload = std::array<uint8_t, 10>;
using Tones = std::array<uint8_t, 79>;
struct Frame {
    Payload payload;
    float time_seconds;
    float frequency_hz;
    int sync_score;
};
struct DecodeResult {
    std::vector<Frame> frames;
    int candidates;
};
Tones encode_tones(Payload const& payload);
std::vector<float> encode_slot(Payload const& payload, float frequency_hz, float peak_amplitude = 0.4f);
DecodeResult decode_slot(std::vector<float> const& pcm);
}
