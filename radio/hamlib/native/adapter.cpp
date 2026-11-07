#include "adapter.hpp"
#include <hamlib/rig.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <exception>
#include <mutex>
#include <stdexcept>

namespace cbcat {
namespace {
std::mutex gate;
void require_ok(int code) {
    if (code != RIG_OK) throw std::runtime_error(std::string("Hamlib: ") + rigerror(code));
}
void initialize() {
    static const bool initialized = [] {
        rig_set_debug(RIG_DEBUG_NONE);
        require_ok(rig_load_all_backends());
        return true;
    }();
    (void) initialized;
}
struct Collection { std::vector<Model> rows; bool overflow = false; std::exception_ptr failure; };
int collect(const rig_caps* caps, rig_ptr_t data) {
    auto& result = *static_cast<Collection*>(data);
    if (result.rows.size() >= 4096) { result.overflow = true; return 0; }
    try {
        // Upstream static metadata; keep it as metadata rather than opening a rig.
        result.rows.push_back({static_cast<int>(caps->rig_model), caps->mfg_name ? caps->mfg_name : "Unknown",
            caps->model_name ? caps->model_name : "Unknown", rig_strstatus(caps->status),
            static_cast<int>(caps->port_type), caps->serial_rate_min, caps->serial_rate_max,
            caps->get_freq != nullptr, caps->get_mode != nullptr, caps->get_ptt != nullptr});
        return 1;
    } catch (...) { result.failure = std::current_exception(); return 0; }
}
struct RigDeleter { void operator()(RIG* rig) const { if (rig) { rig_close(rig); rig_cleanup(rig); } } };
}
std::string version() { return hamlib_version; }
std::vector<Model> catalog() {
    std::lock_guard<std::mutex> lock(gate);
    initialize();
    Collection result;
    // Reserve before calling into C: a bounded catalog is expected for this pin.
    result.rows.reserve(4096);
    require_ok(rig_list_foreach(collect, &result));
    if (result.failure) std::rethrow_exception(result.failure);
    if (result.overflow || result.rows.empty()) throw std::runtime_error("Hamlib catalog size is invalid");
    std::sort(result.rows.begin(), result.rows.end(), [](const Model& a, const Model& b) { return a.id < b.id; });
    for (size_t i = 1; i < result.rows.size(); ++i)
        if (result.rows[i-1].id == result.rows[i].id) throw std::runtime_error("Duplicate Hamlib model ID");
    return result.rows;
}
Dummy check_dummy() {
    std::lock_guard<std::mutex> lock(gate);
    initialize();
    // Hardcoded software backend: no model ID or device path is accepted from JNI.
    std::unique_ptr<RIG, RigDeleter> rig(rig_init(RIG_MODEL_DUMMY));
    if (!rig) throw std::runtime_error("Cannot initialize Hamlib Dummy");
    require_ok(rig_open(rig.get()));
    require_ok(rig_set_freq(rig.get(), RIG_VFO_A, 27265000.0));
    require_ok(rig_set_mode(rig.get(), RIG_VFO_A, RIG_MODE_USB, 2400));
    require_ok(rig_set_ptt(rig.get(), RIG_VFO_A, RIG_PTT_OFF));
    freq_t frequency = 0; rmode_t mode = RIG_MODE_NONE; pbwidth_t width = 0; ptt_t ptt = RIG_PTT_ON;
    require_ok(rig_get_freq(rig.get(), RIG_VFO_A, &frequency));
    require_ok(rig_get_mode(rig.get(), RIG_VFO_A, &mode, &width));
    require_ok(rig_get_ptt(rig.get(), RIG_VFO_A, &ptt));
    if (!std::isfinite(frequency) || frequency != 27265000.0 || mode != RIG_MODE_USB || ptt != RIG_PTT_OFF)
        throw std::runtime_error("Hamlib Dummy round trip failed");
    require_ok(rig_close(rig.get()));
    require_ok(rig_cleanup(rig.release()));
    return {static_cast<int64_t>(frequency), true, true};
}
}
