#include "engine.hpp"
#include "ft8/encode.h"
#include "common/monitor.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <limits>

namespace eightcb {
DecodeSettings decode_settings(int profile) {
    switch (profile) {
        case 0: return {24, 12, 2000};
        case 1: return {60, 25, 4000};
        default: throw std::invalid_argument("Unknown FT8 decode profile");
    }
}
Tones encode_tones(Payload const& payload) {
    if (payload[9] & 7) throw std::invalid_argument("The final three payload padding bits must be zero");
    Tones tones{};
    ft8_encode(payload.data(), tones.data());
    return tones;
}

std::vector<float> encode_slot(Payload const& payload, float frequency_hz, float peak_amplitude, StopRequested const& stop) {
    WorkBudget budget(2000, stop);
    budget.check(WorkStage::preparing);
    if (!std::isfinite(frequency_hz) || frequency_hz < 200 || frequency_hz > 2900)
        throw std::invalid_argument("Audio frequency must be between 200 and 2900 Hz");
    if (!std::isfinite(peak_amplitude) || peak_amplitude < .01f || peak_amplitude > 1)
        throw std::invalid_argument("Peak amplitude must be between 0.01 and 1");
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
    for (int symbol=0; symbol<79; ++symbol) {
        budget.check(WorkStage::encoding);
        for (int j=0; j<3*sps; ++j)
            phase_step[symbol*sps+j] += (2*pi/sps)*tones[symbol]*pulse[j];
    }
    for (int j=0; j<2*sps; ++j) {
        phase_step[j] += (2*pi/sps)*tones.front()*pulse[j+sps];
        phase_step[j+wave_samples] += (2*pi/sps)*tones.back()*pulse[j];
    }
    std::vector<float> slot(slot_samples, 0);
    double phase=0;
    for (int i=0; i<wave_samples; ++i) {
        if (i%sps==0) budget.check(WorkStage::encoding);
        slot[lead_samples+i] = float(peak_amplitude*std::sin(phase));
        phase = std::fmod(phase+phase_step[i+sps], 2*pi);
    }
    for (int i=0; i<sps/8; ++i) {
        float envelope = float((1-std::cos(pi*i/(sps/8)))/2);
        slot[lead_samples+i] *= envelope;
        slot[lead_samples+wave_samples-1-i] *= envelope;
    }
    budget.check(WorkStage::encoding);
    return slot;
}

// A local Hann-window tone/noise estimate, not WSJT-CB's SNR estimator.
// 80 ms symbol centers avoid most GFSK transitions. Noise probes lie outside
// the eight-tone band; interference or colored noise can bias this estimate.
static float estimate_snr(std::vector<float> const& pcm, Frame const& frame, WorkBudget const& budget) {
    auto tones = encode_tones(frame.payload);
    constexpr int count = 960;
    constexpr double pi = 3.14159265358979323846;
    std::array<double, count> window{};
    double sum = 0, squares = 0;
    for (int i=0; i<count; ++i) {
        window[i] = .5 - .5*std::cos(2*pi*i/count);
        sum += window[i]; squares += window[i]*window[i];
    }
    auto power = [&](int start, double frequency) {
        double real=0, imaginary=0;
        double step=2*pi*frequency/sample_rate;
        // Recurrence keeps this diagnostic small compared with candidate decoding.
        double c=1, s=0, cs=std::cos(step), sn=std::sin(step);
        for (int i=0; i<count; ++i) {
            double sample=pcm[start+i]*window[i];
            real += sample*c; imaginary += sample*s;
            double next=c*cs-s*sn; s=s*cs+c*sn; c=next;
        }
        return real*real+imaginary*imaginary;
    };
    double signal=0, noise=0;
    int used=0;
    for (int symbol=1; symbol<78; ++symbol) {
        budget.check(WorkStage::snr);
        int start=int(std::lround(frame.time_seconds*sample_rate))+symbol*1920+480;
        if (start<0 || start+count>int(pcm.size())) continue;
        signal += power(start, frame.frequency_hz+6.25*tones[symbol]);
        for (double offset : {-150.0, -100.0, 150.0, 200.0})
            noise += power(start, frame.frequency_hz+offset)/4;
        ++used;
    }
    if (!used || noise/used < 1e-10 || signal<=noise)
        return std::numeric_limits<float>::quiet_NaN();
    double ratio=(signal-noise)/noise * sample_rate*squares/(2500*sum*sum);
    return float(10*std::log10(ratio));
}

DecodeResult decode_slot(std::vector<float> const& pcm, int profile, StopRequested const& stop) {
    auto settings=decode_settings(profile);
    WorkBudget budget(settings.deadline_ms, stop);
    budget.check(WorkStage::preparing);
    if (pcm.size() != slot_samples && pcm.size() != early_window_samples)
        throw std::invalid_argument("Expected 13.5 or 15 seconds of actual 12 kHz PCM");
    for (float value : pcm) if (!std::isfinite(value) || std::abs(value)>1)
        throw std::invalid_argument("PCM samples must be finite and normalized to [-1, 1]");
    monitor_config_t config{200, 3000, sample_rate, 2, 2, FTX_PROTOCOL_FT8};
    monitor_t monitor{};
    monitor_init(&monitor, &config);
    // All work is per call; no global hash cache or message-policy state is used.
    struct Cleanup { monitor_t* p; ~Cleanup() { monitor_free(p); } } cleanup{&monitor};
    for (int offset=0; offset+monitor.block_size<=static_cast<int>(pcm.size()); offset+=monitor.block_size) {
        budget.check(WorkStage::monitoring);
        monitor_process(&monitor, pcm.data()+offset);
    }
    std::array<ftx_candidate_t, 60> candidates{};
    DecodeResult result{};
    budget.check(WorkStage::searching);
    result.candidates = ftx_find_candidates(&monitor.wf, settings.candidates, candidates.data(), 10);
    budget.check(WorkStage::searching);
    for (int i=0; i<result.candidates; ++i) {
        budget.check(WorkStage::decoding);
        ++result.processed;
        ftx_message_t message{};
        ftx_decode_status_t status{};
        auto const& candidate=candidates[i];
        if (!ftx_decode_candidate(&monitor.wf, &candidate, settings.iterations, &message, &status)) continue;
        if (status.ldpc_errors != 0 || status.crc_calculated != status.crc_extracted) continue;
        Frame frame{};
        std::copy(message.payload, message.payload+10, frame.payload.begin());
        frame.payload[9] &= 0xf8;
        if (std::any_of(result.frames.begin(), result.frames.end(), [&](Frame const& old) {return old.payload==frame.payload;})) continue;
        frame.frequency_hz = (monitor.min_bin+candidate.freq_offset+float(candidate.freq_sub)/monitor.wf.freq_osr)/monitor.symbol_period;
        // With this 2x/2x monitor, the trailing FFT window delays the symbol
        // index by one symbol relative to the actual waveform onset.
        frame.time_seconds = (candidate.time_offset+float(candidate.time_sub)/monitor.wf.time_osr-1)*monitor.symbol_period;
        frame.sync_score = candidate.score;
        frame.estimated_snr_db = estimate_snr(pcm, frame, budget);
        result.frames.push_back(frame);
    }
    budget.check(WorkStage::decoding);
    return result;
}
}
