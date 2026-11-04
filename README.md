# 8CBdroid

Scheletro di un’app Android per FT8 in banda CB (11 m), con compatibilità
prevista con [WSJT-CB](https://github.com/vash909/WSJT-CB).

**Stato: ambiente di sviluppo e interfaccia demo.** L’app si compila e si avvia,
ma non decodifica, non trasmette, non apre dispositivi USB e non si connette
ancora al desktop. I pulsanti operativi sono disabilitati.

## Percorsi previsti

- **USB OTG / CAT:** controllo delle radio tramite Hamlib/JNI oppure driver CAT
  Android equivalenti. CAT/PTT e audio USB sono percorsi distinti.
- **VOX:** ricezione dal microfono e uscita dall’altoparlante del telefono per
  accoppiamento acustico con una radio dotata di VOX.
- **LAN:** integrazione con il protocollo UDP binario già esposto da WSJT-CB,
  per stato, decodifiche, log e comandi supportati. Le API non trasportano audio.

## Avvio rapido su questo PC / Linux x86_64

Prerequisiti: Python 3.12+, `curl`, `unzip`, Git e accesso a Internet.

```bash
python3 scripts/bootstrap.py
source scripts/env.sh
./gradlew :app:assembleDebug
bash scripts/check.sh
```

Il bootstrap scarica JDK 17 e Android SDK in `.tooling/`, verifica i checksum
pubblicati e mostra le licenze SDK. Non installa pacchetti di sistema.
L’ambiente locale e `local.properties` sono esclusi da Git.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Con un telefono collegato e il debug USB autorizzato:

```bash
source scripts/env.sh
adb devices
./gradlew :app:installDebug
```

In Android Studio aprire la radice del progetto e configurare Gradle JDK 17
(`.tooling/jdk-17` su questo PC). Il file `local.properties` indica l’SDK locale.
Su altri sistemi vedere [la guida di sviluppo](docs/DEVELOPMENT.md).

## Struttura

| Percorso | Responsabilità |
| --- | --- |
| `app/` | App Android, Compose, quattro sezioni demo e adattatori segnaposto |
| `core/` | Modelli, nominativi CB, profilo FT8 e contratti radio/audio/DSP |
| `protocol/wsjtcb/` | Costanti UDP, codec offline di intestazione/heartbeat e contratto LAN |
| `native/` | Punto di partenza CMake/C++ opzionale per futuri motori nativi |
| `scripts/` | Preparazione ambiente e controlli locali |
| `.github/workflows/` | Build, test JVM, lint e APK debug su GitHub Actions |

Versioni fissate: JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21,
Compose BOM 2025.10.01, compile/target SDK 36, Android minimo 8.0 / API 26.
Non è richiesto NDK per questa prima build.

## Compatibilità da sviluppare

Il profilo usa i parametri FT8 presenti in WSJT-CB (slot 15 s, PCM DSP 12 kHz,
79 simboli) e le sue frequenze predefinite 27.265 / 27.045 MHz. Il controllo
del nominativo riprende la grammatica CB completa del progetto; **non è un
encoder FT8**. Hash, messaggi type 4 e sequenze CB richiedono test di
interoperabilità con il motore desktop.

Il riferimento analizzato è WSJT-CB `94f776d54946fe43db5e0bb9c966b1595c7b7d97`.
Il desktop non è una dipendenza scaricata o compilata dall’app.

- [Architettura e integrazione Hamlib/USB/audio](docs/ARCHITECTURE.md)
- [Contratto LAN verificato nei sorgenti](docs/WSJT_CB_INTEGRATION.md)
- [Ambiente, build e debug](docs/DEVELOPMENT.md)
- [Roadmap](docs/ROADMAP.md)

## Licenza

GPL-3.0-only, vedere [LICENSE](LICENSE). Attribuzioni e dipendenze sono
descritte in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
