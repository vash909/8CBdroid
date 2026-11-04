# Ambiente di sviluppo

## Linux x86_64

```bash
python3 scripts/bootstrap.py
source scripts/env.sh
bash scripts/check.sh
```

Il bootstrap usa i metadati ufficiali Adoptium per l’ultima patch JDK 17 e
quelli Google per i command-line tools SDK; verifica i checksum pubblicati.
Le versioni effettivamente scaricate sono registrate in `.tooling/*-source.json`.
Piattaforma Android 36 e build-tools 35.0.0 sono fissati esplicitamente.
Per aggiornare JDK/CLI, rinominare le rispettive directory locali e rieseguire
il bootstrap. Le directory presenti vengono riutilizzate.

`source scripts/env.sh` seleziona gli strumenti del progetto senza modificare
la configurazione globale della shell. Ripeterlo in ogni nuovo terminale.

Il bootstrap non installa Android Studio, emulatori, immagini di sistema o
NDK. Per sviluppare la UI si possono usare le preview Compose di Android
Studio e un telefono con debug USB. L’IDE può essere installato separatamente
dal [sito ufficiale](https://developer.android.com/studio).

## Android Studio / altri sistemi

Installare JDK 17 e Android SDK tramite Android Studio o command-line tools.
Selezionare SDK Platform 36, Build-Tools 35.0.0 e Platform-Tools. Impostare
`JAVA_HOME`, `ANDROID_HOME` e creare un `local.properties` non versionato:

```properties
sdk.dir=/percorso/assoluto/Android/Sdk
```

Aprire la radice del repository, attendere il Gradle sync e lanciare `app`.
Windows: usare `gradlew.bat`. Il wrapper è incluso, non serve Gradle globale.

## Comandi utili

```bash
./gradlew :core:test :protocol:wsjtcb:test :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
adb devices
./gradlew :app:installDebug
adb shell am start -n net.vash.ft8cbdroid/.MainActivity
```

I test JVM verificano grammatica CB, locator Maidenhead, formato header/heartbeat
UDP, ViewModel e persistenza DataStore su file dopo chiusura e riapertura.
Non verificano radio, audio, LAN reale o interoperabilità DSP. Per questi
serviranno test su hardware e fixture upstream nella fase successiva.

## GitHub

Il workflow Android compila, esegue test e lint sui push a `main` e sulle PR e conserva
l’APK debug come artifact per 14 giorni. Non firma release e non pubblica su
Play Store. I workflow hanno permessi di sola lettura sul repository.

Prima di un commit eseguire `bash scripts/check.sh`. Non versionare SDK,
cache, `local.properties`, credenziali, chiavi di firma o registrazioni audio.
La versione del wrapper è verificata con lo SHA-256 della distribuzione.

Lavorare su branch piccoli e aprire una PR per ogni comportamento verificabile.
Scrivere titoli e descrizioni brevi e chiari. Dopo PR e build, attendere l’OK
esplicito del proprietario prima del merge; non abilitare auto-merge.

## Prova delle impostazioni sul telefono

1. Installare la build della PR e aprire la sezione Stazione.
2. Inserire `1at106` e `jn45ab`: i campi diventano maiuscoli.
3. Premere **Salva impostazioni** e attendere la conferma sullo schermo.
4. Ruotare il telefono, cambiare sezione e tornare: i valori restano presenti.
5. Arrestare il processo e riaprire l’app:

```bash
adb shell am force-stop net.vash.ft8cbdroid
adb shell am start -n net.vash.ft8cbdroid/.MainActivity
```

6. Verificare che nominativo e locator salvati siano ripristinati.
7. Provare `1AT` oppure `SS99`: il campo segnala l’errore e Salva resta disabilitato.
8. Correggere i valori e salvare una seconda coppia; ripetere la riapertura.

Sono persistenti solo nominativo e locator. Le modifiche non salvate non
sostituiscono i dati su disco. Gli altri controlli sono ancora dimostrativi.

Riferimento per la toolchain scelta:
[AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
