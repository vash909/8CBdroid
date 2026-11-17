int main(int argc, char **argv) {
  QCoreApplication app(argc, argv);
  try {
    if (argc != 3) throw std::runtime_error("Expected write/read and file path");
    QFile input(QString::fromLocal8Bit(argv[2]));
    if (!input.open(QIODevice::ReadOnly)) throw std::runtime_error("Cannot read input");
    auto data = input.readAll(); input.close();
    if (QString::fromLocal8Bit(argv[1]) == "write") {
      auto values = QJsonDocument::fromJson(data).array();
      QByteArray out = "ADIF Export\n<adif_ver:5>3.1.1<programid:7>WSJT-CB<EOH>\n";
      for (auto value : values) {
        auto o = value.toObject(); auto s = [&](char const *key) { return o.value(key).toString(); };
        out += desktop_QSOToADIF(s("call"), s("grid"), "FT8", s("sent"), s("received"),
          QDateTime::fromString(s("start"), Qt::ISODate), QDateTime::fromString(s("end"), Qt::ISODate),
          s("band"), s("comment"), "", s("frequency"), s("station"), s("station_grid"), "", "", "", "", "", "", "", "");
        out += " <EOR>\n\n";
      }
      QFile output; output.open(stdout, QIODevice::WriteOnly); output.write(out);
    } else {
      // The real desktop loader is exercised, with DXCC lookup stubbed out.
      AD1CCty prefixes; auto worked = loader(QString::fromLocal8Bit(argv[2]), &prefixes);
      QJsonArray entries;
      for (auto const& w : worked) entries.append(QJsonObject{{"call", w.call}, {"grid", w.grid}, {"band", w.band}, {"mode", w.mode}});
      // Audit the exact desktop field extractor for every row, including FREQ/UTC.
      auto text = QString::fromLatin1(data); int header = text.indexOf("<EOH>", 0, Qt::CaseInsensitive);
      if (header >= 0) text.remove(0, header + 5);
      QJsonArray fields;
      while (true) {
        int end = text.indexOf("<EOR>", 0, Qt::CaseInsensitive); if (end < 0) break;
        auto record = text.left(end + 5); text.remove(0, end + 5);
        QJsonObject row;
        for (auto name : {"CALL", "STATION_CALLSIGN", "FREQ", "QSO_DATE", "TIME_ON", "QSO_DATE_OFF", "TIME_OFF", "RST_SENT", "RST_RCVD"}) row.insert(name, extractField(record, name));
        fields.append(row);
      }
      QFile output; output.open(stdout, QIODevice::WriteOnly);
      output.write(QJsonDocument(QJsonObject{{"worked", entries}, {"fields", fields}}).toJson(QJsonDocument::Compact));
    }
  } catch (std::exception const& e) { fprintf(stderr, "%s\n", e.what()); return 1; }
}
