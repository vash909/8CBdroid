// Harness-only dependencies. Desktop ADIF methods are extracted byte-for-byte.
#include <QtCore>
#include <stdexcept>
#include <vector>
using LoaderException = std::runtime_error;
struct Configuration {
  enum class SpecialOperatingActivity { FIELD_DAY, RTTY };
  SpecialOperatingActivity special_op_id() const { throw std::runtime_error("Contest activity is outside this harness"); }
};
static Configuration const *config_ = nullptr;
struct Entity { QString entity_name, continent; int CQ_zone = 0, ITU_zone = 0; };
struct AD1CCty { Entity lookup(QString const&) const { return {}; } };
struct Worked {
  QString call, grid, band, mode;
  Worked(QString c, QString g, QString b, QString m, QString, QString, int, int) : call(c), grid(g), band(b), mode(m) {}
};
struct worked_before_database_type : std::vector<Worked> {
  template<class... Args> void emplace(Args&&... args) { emplace_back(std::forward<Args>(args)...); }
};
