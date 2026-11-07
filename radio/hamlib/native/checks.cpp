#include "adapter.hpp"
#include <iostream>
#include <stdexcept>
int main() {
    const auto models = cbcat::catalog();
    if (models.size() < 100) throw std::runtime_error("Missing broad Hamlib model catalog");
    bool dummy = false, icom = false, yaesu = false, kenwood = false;
    for (const auto& m : models) {
        if (m.name.empty() || m.manufacturer.empty()) throw std::runtime_error("Missing model metadata");
        dummy |= m.id == 1 && m.read_frequency && m.read_mode;
        icom |= m.manufacturer == "Icom";
        yaesu |= m.manufacturer == "Yaesu";
        kenwood |= m.manufacturer == "Kenwood";
    }
    if (!(dummy && icom && yaesu && kenwood)) throw std::runtime_error("Expected model families missing");
    for (int i = 0; i < 20; ++i) {
        auto d = cbcat::check_dummy();
        if (d.frequency != 27265000 || !d.usb || !d.ptt_off) throw std::runtime_error("Dummy lifecycle failed");
    }
    if (cbcat::catalog().size() != models.size()) throw std::runtime_error("Unstable catalog");
    std::cout << "PASS: " << cbcat::version() << ", " << models.size()
              << " catalog entries; 20 software-only Dummy open/read/close cycles\n";
}
