#pragma once
#include <cstdint>
#include <string>
#include <vector>
namespace cbcat {
struct Model {
    int id;
    std::string manufacturer, name, status;
    int port, baud_min, baud_max;
    bool read_frequency, read_mode, read_ptt;
};
struct Dummy { int64_t frequency; bool usb; bool ptt_off; };
std::string version();
std::vector<Model> catalog();
Dummy check_dummy();
}
