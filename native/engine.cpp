#include "engine.hpp"
#include "ft8/encode.h"
#include "common/monitor.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace eightcb {
Tones encode_tones(Payload const& payload) {
    if (payload[9] & 7) throw std::invalid_argument("The final three payload padding bits must be zero");
    Tones tones{};
    ft8_encode(payload.data(), tones.data());
    return tones;
}

std::vector<float> encode_slot(Payload const& payload, float frequency_hz) {
    if (!std::isfinite(frequency_hz) || frequency_hz < 200 || frequency_hz > 2900)
        throw std::invalid_argument("Audio frequency must be between 200 and 2900 Hz");
    auto tones = encode_tones(payload);
    constexpr int sps = 1920, wave_samples = 79 * sps, lead_samples = 9600;
    constexpr double pi = 3.14159265358979323846;
    // GFSK construction follows ft8_lib demo/gen_ft8.c (MIT, Karlis Goba).
    // Heap buffers avoid its large variable-length arrays on Android JNI stacks.
    std::vector<double> pulse(3 * sps), phase_step(wave_samples + 2 * sps, 2 * pi * frequency_hz / sample_rate);
    for (int i=0; i<3*sps; ++i) {
        double t = double(i)/sps - 1.5;
        double k = pi * std::sqrt(2.0/std::log(2.0)) * 2.0;
        pulse[i] = (std::erf(k*(t+0.5))-std::erf(k*(t-0.5)))/2;
    }
    for (int symbol=0; symbol<79; ++symbol)
        for (int j=0; j<3*sps; ++j)
            phase_step[symbol*sps+j] += (2*pi/sps)*tones[symbol]*pulse[j];
    for (int j=0; j<2*sps; ++j) {
        phase_step[j] += (2*pi/sps)*tones.front()*pulse[j+sps];
        phase_step[j+wave_samples] += (2*pi/sps)*tones.back()*pulse[j];
    }
    std::vector<float> slot(slot_samples, 0);
    double phase=0;
    for (int i=0; i<wave_samples; ++i) {
        slot[lead_samples+i] = float(0.4*std::sin(phase));
        phase = std::fmod(phase+phase_step[i+sps], 2*pi);
    }
    for (int i=0; i<sps/8; ++i) {
        float envelope = float((1-std::cos(pi*i/(sps/8)))/2);
        slot[lead_samples+i] *= envelope;
        slot[lead_samples+wave_samples-1-i] *= envelope;
    }
    return slot;
}

DecodeResult decode_slot(std::vector<float> const& pcm) {
    if (pcm.size() != slot_samples) throw std::invalid_argument("Expected one 15-second 12 kHz PCM slot");
    for (float value : pcm) if (!std::isfinite(value) || std::abs(value)>1)
        throw std::invalid_argument("PCM samples must be finite and normalized to [-1, 1]");
    monitor_config_t config{200, 3000, sample_rate, 2, 2, FTX_PROTOCOL_FT8};
    monitor_t monitor{};
    monitor_init(&monitor, &config);
    // All work is per call; no global hash cache or message-policy state is used.
    struct Cleanup { monitor_t* p; ~Cleanup() { monitor_free(p); } } cleanup{&monitor};
    for (int offset=0; offset+monitor.block_size<=slot_samples; offset+=monitor.block_size)
        monitor_process(&monitor, pcm.data()+offset);
    std::array<ftx_candidate_t, 60> candidates{};
    DecodeResult result{};
    result.candidates = ftx_find_candidates(&monitor.wf, int(candidates.size()), candidates.data(), 10);
    for (int i=0; i<result.candidates; ++i) {
        ftx_message_t message{};
        ftx_decode_status_t status{};
        auto const& candidate=candidates[i];
        if (!ftx_decode_candidate(&monitor.wf, &candidate, 25, &message, &status)) continue;
        if (status.ldpc_errors != 0 || status.crc_calculated != status.crc_extracted) continue;
        Frame frame{};
        std::copy(message.payload, message.payload+10, frame.payload.begin());
        frame.payload[9] &= 0xf8;
        if (std::any_of(result.frames.begin(), result.frames.end(), [&](Frame const& old) {return old.payload==frame.payload;})) continue;
        frame.frequency_hz = (monitor.min_bin+candidate.freq_offset+float(candidate.freq_sub)/monitor.wf.freq_osr)/monitor.symbol_period;
        frame.time_seconds = (candidate.time_offset+float(candidate.time_sub)/monitor.wf.time_osr)*monitor.symbol_period;
        frame.sync_score = candidate.score;
        result.frames.push_back(frame);
    }
    return result;
}
}
