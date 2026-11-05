#include "engine.hpp"
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

static eightcb::Payload payload(std::string const& bits) {
    if (bits.size()!=77 || bits.find_first_not_of("01")!=std::string::npos) throw std::invalid_argument("Expected 77 bits");
    eightcb::Payload result{};
    for (int i=0;i<77;++i) result[i/8] |= (bits[i]=='1') << (7-i%8);
    return result;
}
static std::string bits(eightcb::Payload const& p) {
    std::string result;
    for (int i=0;i<77;++i) result += ((p[i/8]>>(7-i%8))&1) ? '1':'0';
    return result;
}
static void decoded(std::vector<float> const& pcm) {
    auto result=eightcb::decode_slot(pcm);
    std::cout << "{\"candidates\":" << result.candidates << ",\"frames\":[";
    bool first=true;
    for (auto const& frame:result.frames) {
        if (!first) std::cout << ',';
        first=false;
        std::cout << "{\"payload77\":\"" << bits(frame.payload) << "\",\"time\":" << frame.time_seconds
                  << ",\"frequency\":" << frame.frequency_hz << ",\"score\":" << frame.sync_score << '}';
    }
    std::cout << "]}\n";
}
int main(int argc, char** argv) {
    try {
        if (argc==3 && std::string(argv[1])=="encode") {
            for (auto tone:eightcb::encode_tones(payload(argv[2]))) std::cout << int(tone);
            std::cout << '\n';
        } else if (argc==3 && std::string(argv[1])=="roundtrip") {
            decoded(eightcb::encode_slot(payload(argv[2]),1000));
        } else if (argc==2 && std::string(argv[1])=="silence") {
            decoded(std::vector<float>(eightcb::slot_samples,0));
        } else if (argc==3 && std::string(argv[1])=="decode") {
            std::ifstream in(argv[2],std::ios::binary);
            char header[44]; in.read(header,44);
            if (!in || std::memcmp(header,"RIFF",4) || std::memcmp(header+8,"WAVE",4)) throw std::runtime_error("Invalid fixture WAV");
            std::vector<int16_t> samples(eightcb::slot_samples);
            in.read(reinterpret_cast<char*>(samples.data()),samples.size()*2);
            if (!in || in.peek()!=std::ifstream::traits_type::eof()) throw std::runtime_error("Invalid fixture sample count");
            std::vector<float> pcm;
            for (auto value:samples) pcm.push_back(value/32768.0f);
            decoded(pcm);
        } else throw std::invalid_argument("Usage: engine_probe encode|roundtrip BITS, decode WAV, or silence");
    } catch (std::exception const& e) {std::cerr << e.what() << '\n';return 1;}
}
