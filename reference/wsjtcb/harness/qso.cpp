// Host adapter. Decision, parser and generation methods are included unchanged.
#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QTimer>
#include <QStringList>
#include <cmath>
#include <functional>
#include <iostream>
#include <stdexcept>
#include <vector>
#include "Radio.hpp"
#include "Decoder/decodedtext.h"
#include "qt_helpers.hpp"

extern "C" void save_dxbase_(char*, FCL);

struct Control : QObject {
  QString text_;
  int value_ = 1500;
  bool checked_ = false, enabled_ = true, visible_ = true, clicked_ = false;
  std::function<void(QString const&)> text_changed;
  std::function<void(bool)> toggled;
  std::vector<Control*>* group = nullptr;
  QString text() const { return text_; }
  QString currentText() const { return text_; }
  int value() const { return value_; }
  bool isChecked() const { return checked_; }
  bool isEnabled() const { return enabled_; }
  bool isVisible() const { return visible_; }
  void setText(QString const& text) {
    if (text_ == text) return;
    text_ = text;
    if (text_changed) text_changed(text_);
  }
  void setValue(int value) { value_ = value; }
  void setChecked(bool checked) {
    if (checked_ == checked) return;
    if (checked && group) for (auto* other : *group) if (other != this) other->setChecked(false);
    checked_ = checked;
    if (toggled) toggled(checked);
  }
  void clear() { setText({}); }
  void click() { clicked_ = true; }
  Control* lineEdit() { return this; }
  void getDxBase(QString const&) {}
  template<class... T> void displayDecodedText(T const&...) {}
  template<class... T> void displayQSY(T const&...) { throw std::runtime_error("Unexpected QSY"); }
  template<class... T> void insertText(T const&...) {}
  template<class... T> void insertLineSpacer(T const&...) {}
};
struct Timer {
  bool requested = false;
  void start(int) { requested = true; }
};
struct TestUI {
#define CONTROL(name) Control name##_storage; Control* name = &name##_storage;
  CONTROL(dxCallEntry) CONTROL(dxGridEntry) CONTROL(RxFreqSpinBox) CONTROL(TxFreqSpinBox)
  CONTROL(tx1) CONTROL(tx2) CONTROL(tx3) CONTROL(tx4) CONTROL(tx5) CONTROL(tx6)
  CONTROL(txrb1) CONTROL(txrb2) CONTROL(txrb3) CONTROL(txrb4) CONTROL(txrb5) CONTROL(txrb6)
  CONTROL(cbAutoSeq) CONTROL(stopTxButton) CONTROL(txFirstCheckBox) CONTROL(cbHoldTxFreq)
  CONTROL(cbShMsgs) CONTROL(cbTx6) CONTROL(cbCQTx) CONTROL(sbSerialNumber) CONTROL(labAz)
  CONTROL(autoButton) CONTROL(actionUse_Dark_Style) CONTROL(decodedTextBrowser2)
  CONTROL(respondComboBox) CONTROL(rptSpinBox)
  CONTROL(DX_Call_Button)
#undef CONTROL
};
struct Configuration {
  enum Type2 { type_2_msg_1_full, type_2_msg_3_full, type_2_msg_5_only };
  QString call = "1AT106", grid = "JN45";
  QString const& my_callsign() const { return call; }
  QString const& my_grid() const { return grid; }
  bool enable_VHF_features() const { return false; }
  bool is_transceiver_online() const { return false; }
  bool NCCC_Sprint() const { return false; }
  bool prompt_to_log() const { return false; }
  bool autoLog() const { return false; }
  bool insert_blank() const { return false; }
  bool DXCC() const { return false; }
  bool ppfx() const { return false; }
  bool quick_call() const { return false; }
  bool superFox() const { return false; }
  bool Individual_Contest_Name() const { return false; }
  QString Contest_Name() const { return {}; }
  QString Field_Day_Exchange() const { return {}; }
  QString RTTY_Exchange() const { return {}; }
  Type2 type_2_msg_gen() const { return type_2_msg_5_only; }
};
enum class SpecOp { NONE, FOX, HOUND, NA_VHF, WW_DIGI, ARRL_DIGI, Q65_PILEUP, FIELD_DAY, RTTY, EU_VHF };
struct MessageBox {
  template<class... T> static void information_message(T const&...) {
    throw std::runtime_error("Unsupported contest-mode prompt");
  }
};
#define LOG_INFO(x) ((void)0)

class MainWindow {
public:
  enum Progress { CALLING, REPLYING, REPORT, ROGER_REPORT, ROGERS, SIGNOFF };
  Configuration m_config;
  TestUI controls;
  TestUI* ui = &controls;
  Progress m_QSOProgress = REPORT;
  SpecOp m_specOp = SpecOp::NONE;
  QString m_baseCall = "1AT106", m_mode = "FT8", m_hisCall = "26AT101", m_xRcvd;
  QString m_nextCall, m_QSOText, m_currentBand = "11m", m_currentBandPeriod = "11m";
  QString m_hisGrid, m_rptRcvd, m_lastCallsign, m_hisCall0, m_rpt, m_CQtype = "CQ", m_cqStr;
  QStringList m_pfx, m_sfx;
  bool m_bCallingCQ = false, m_auto = true, m_sentFirst73 = false, m_bAutoReply = true;
  bool m_bFastMode = false, m_txFirst = false, m_bDoubleClicked = false, m_bTUmsg = false;
  bool no_logging = false, m_bDoubleClickAfterCQnnn = false, m_transmitting = false;
  bool m_band_changed = false, BlankLineInserted = false, m_muted = false, m_bSentReport = false;
  bool m_bShMsgs = false, m_nTx73 = false, m_restart = false, m_send_RR73 = true;
  bool keepTx5 = false, m_gen_message_is_cq = false, m_currentMessageType = false;
  bool blocked = false, keep_msk144_frequency = false, msk144qsy = false;
  Control* m_QSYMessageCreatorWidget = nullptr;
  std::vector<Control*> radio_group;
  int m_ntx = 2, m_maxPoints = 0, m_logBook = 0;
  double m_TRperiod = 15;
  Radio::Frequency m_freqNominal = 27265000, m_msk144basefreq = 0;
  QDateTime m_dateTimeRcvdRR73, m_dateTimeSentTx3;
  Timer logQSOTimer, TxAgainTimer;
  int lookup_count = 0, cease_count = 0;
  bool has_complete_cb_peer(DecodedText const&) const;
  void auto_sequence(DecodedText const&, unsigned, unsigned);
  void processMessage(DecodedText const&, Qt::KeyboardModifiers = {});
  void setTxMsg(int);
  void genCQMsg();
  bool stdCall(QString const&);
  bool is77BitMode() const;
  void genStdMsgs(QString, bool unconditional = false);
  bool shortList(QString) const;
  void on_dxCallEntry_textChanged(QString const&);
  void on_txrb1_toggled(bool);
  void on_txrb2_toggled(bool);
  void on_txrb3_toggled(bool);
  void on_txrb4_toggled(bool);
  void on_txrb5_toggled(bool);
  void on_txrb6_toggled(bool);
  MainWindow() {
    ui->dxCallEntry->text_changed = [this](QString const& text) { on_dxCallEntry_textChanged(text); };
    radio_group = {ui->txrb1,ui->txrb2,ui->txrb3,ui->txrb4,ui->txrb5,ui->txrb6};
    for (auto* control : radio_group) control->group = &radio_group;
    ui->txrb1->toggled = [this](bool b) { on_txrb1_toggled(b); };
    ui->txrb2->toggled = [this](bool b) { on_txrb2_toggled(b); };
    ui->txrb3->toggled = [this](bool b) { on_txrb3_toggled(b); };
    ui->txrb4->toggled = [this](bool b) { on_txrb4_toggled(b); };
    ui->txrb5->toggled = [this](bool b) { on_txrb5_toggled(b); };
    ui->txrb6->toggled = [this](bool b) { on_txrb6_toggled(b); };
  }
  // Presentation effects only; msgtype's output text is t.toUpper() upstream.
  void msgtype(QString t, Control* target) { target->setText(t.toUpper()); }
  static QString tr(char const* text) { return QString::fromUtf8(text); }
  void lookup() { ++lookup_count; }
  void cease_auto_Tx_after_QSO() { ++cease_count; }
  void auto_tx_mode(bool) { throw std::runtime_error("Unexpected TX activation"); }
  void setRig(Radio::Frequency) { throw std::runtime_error("Unexpected CAT operation"); }
  void useNextCall() { throw std::runtime_error("Unsupported next-call queue"); }
  void on_stopTxButton_clicked() { ui->stopTxButton->click(); }
  void set_dateTimeQSO(int) {}
  void statusChanged() {}
  void statusUpdate() {}
  void check_button_color() {}
  void clearDX() { throw std::runtime_error("Unsupported Hound clear"); }
};

#include "qso_methods.inc"

static char const* progress_names[] = {"CALLING", "REPLYING", "REPORT", "ROGER_REPORT", "ROGERS", "SIGNOFF"};

static QJsonObject parsed(DecodedText const& message, MainWindow const& w) {
  QString call, grid, report;
  message.deCallAndGrid(call, grid);
  message.report(w.m_baseCall, Radio::base_callsign(w.ui->dxCallEntry->text()), report);
  return {{"standard", message.isStandardMessage()}, {"words", QJsonArray::fromStringList(message.messageWords())},
          {"de_call", call}, {"grid", grid}, {"call", message.call()}, {"cq_call", message.CQersCall()},
          {"report", report}, {"snr_report", message.report()}, {"df", message.frequencyOffset()},
          {"snr", message.snr()}, {"time", int(message.timeInSeconds())},
          {"complete_peer", w.has_complete_cb_peer(message)}};
}

static QJsonArray messages(MainWindow const& w) {
  QJsonArray values;
  for (auto* control : {w.ui->tx1,w.ui->tx2,w.ui->tx3,w.ui->tx4,w.ui->tx5,w.ui->tx6}) values.append(control->text());
  return values;
}

static QJsonObject snapshot(MainWindow const& w) {
  return {{"progress", progress_names[w.m_QSOProgress]}, {"tx", w.m_ntx},
          {"peer", w.ui->dxCallEntry->text()}, {"peer_grid", w.ui->dxGridEntry->text()},
          {"received_report", w.m_rptRcvd}, {"calling_cq", w.m_bCallingCQ},
          {"sent_first_73", w.m_sentFirst73}, {"rx_df", w.ui->RxFreqSpinBox->value()},
          {"tx_df", w.ui->TxFreqSpinBox->value()}, {"tx_first", w.m_txFirst},
          {"stop_requested", w.ui->stopTxButton->clicked_}, {"log_requested", w.logQSOTimer.requested},
          {"cease_requested", w.cease_count > 0}, {"processed", w.lookup_count}, {"messages", messages(w)}};
}

static void configure(MainWindow& w, QJsonObject const& s) {
  w.m_config.call = s.value("local").toString("1AT106");
  w.m_config.grid = s.value("grid").toString("JN45");
  w.m_baseCall = Radio::base_callsign(w.m_config.call);
  w.m_hisCall = s.value("peer").toString("26AT101");
  w.ui->dxCallEntry->setText(w.m_hisCall);
  w.ui->dxGridEntry->setText(s.value("peer_grid").toString());
  w.m_bCallingCQ = s.value("calling_cq").toBool(false);
  w.m_auto = s.value("auto").toBool(true);
  w.m_sentFirst73 = s.value("sent_first_73").toBool(false);
  w.m_bAutoReply = s.value("auto_reply").toBool(true);
  w.ui->tx1->enabled_ = s.value("tx1_enabled").toBool(true);
  w.ui->cbAutoSeq->checked_ = s.value("auto_seq").toBool(true);
  w.ui->cbAutoSeq->enabled_ = s.value("auto_seq_enabled").toBool(true);
  w.ui->cbAutoSeq->visible_ = s.value("auto_seq_visible").toBool(true);
  w.ui->RxFreqSpinBox->value_ = s.value("rx_df").toInt(1500);
  w.ui->TxFreqSpinBox->value_ = s.value("tx_df").toInt(1500);
  w.m_ntx = s.value("tx").toInt(2);
  auto progress = s.value("progress").toString("REPORT");
  bool found = false;
  for (int i=0;i<6;++i) if (progress == progress_names[i]) {
    w.m_QSOProgress = MainWindow::Progress(i); found = true;
  }
  if (!found) throw std::runtime_error("Unknown QSO progress");
  w.genStdMsgs(s.value("report").toString("-07"), true);
}

int main(int argc, char** argv) {
  QCoreApplication application(argc, argv);
  try {
    std::string input;
    std::getline(std::cin, input);
    QJsonParseError error;
    auto document = QJsonDocument::fromJson(QByteArray::fromStdString(input), &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) throw std::runtime_error("Invalid scenario JSON");
    auto request = document.object();
    MainWindow w;
    configure(w, request.value("state").toObject());
    MainWindow remote;
    auto duplex = request.contains("remote_state");
    if (duplex) configure(remote, request.value("remote_state").toObject());
    QJsonObject result {{"id", request.value("id")}, {"initial", snapshot(w)}};
    if (duplex) result.insert("remote_initial", snapshot(remote));
    QJsonArray steps;
    for (auto const& value : request.value("steps").toArray()) {
      auto step = value.toObject();
      auto op = step.value("op").toString();
      QJsonObject output {{"op",op}};
      if (op == "parse" || op == "auto" || op == "process") {
        auto line = step.value("line").toString();
        DecodedText message(line);
        output.insert("parsed", parsed(message, w));
        if (op == "auto") w.auto_sequence(message, step.value("start_tolerance").toInt(25), step.value("stop_tolerance").toInt(50));
        else if (op == "process") w.processMessage(message);
        output.insert("line", line);
      } else if (op == "relay") {
        if (!duplex) throw std::runtime_error("Relay requires two station contexts");
        auto from_remote = step.value("from").toString() == "remote";
        auto& sender = from_remote ? remote : w;
        auto& receiver = from_remote ? w : remote;
        auto slot = step.value("tx").toInt(sender.m_ntx);
        auto text = messages(sender).at(slot - 1).toString().trimmed();
        auto line = "120000 -12  0.0 1500 ~  " + text;
        DecodedText message(line);
        output.insert("from", step.value("from"));
        output.insert("tx", slot);
        output.insert("line", line);
        output.insert("parsed", parsed(message, receiver));
        output.insert("via", step.value("via"));
        if (step.value("via").toString() == "process") receiver.processMessage(message);
        else receiver.auto_sequence(message, 25, 50);
      } else if (op == "context") {
        // Explicit simulated external events, never attributed to received code.
        auto s = step.value("state").toObject();
        auto& station = step.value("side").toString() == "remote" ? remote : w;
        if (s.contains("sent_first_73")) station.m_sentFirst73 = s.value("sent_first_73").toBool();
        if (s.contains("peer")) station.ui->dxCallEntry->setText(s.value("peer").toString());
        output.insert("state", s);
        if (step.contains("side")) output.insert("side", step.value("side"));
      } else throw std::runtime_error("Unknown scenario operation");
      output.insert("result", snapshot(w));
      if (duplex) output.insert("remote_result", snapshot(remote));
      steps.append(output);
    }
    result.insert("steps", steps);
    std::cout << QJsonDocument(result).toJson(QJsonDocument::Compact).constData() << '\n';
  } catch (std::exception const& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
