# Architettura iniziale

La UI Android dipende da `core` e `protocol:wsjtcb`. I due moduli JVM sono
testabili senza emulatore. Non sono presenti singleton hardware, servizi in
background o operazioni radio all’avvio.

```text
app (Compose + adattatori Android)
 ├── core (stazione, radio, audio, contratto DSP)
 └── protocol:wsjtcb (codec UDP offline, contratto bridge)
      └── core

native (CMake opzionale, fuori dalla build Gradle attuale)
```

## Radio: Hamlib oppure CAT Android

`RadioController` definisce connessione, frequenza e PTT. L’adattatore attuale
restituisce `BackendResult.Unavailable` e dichiara capacità disabilitate.

Due implementazioni future possono convivere dietro lo stesso contratto:

1. Hamlib compilata con Android NDK e collegata tramite JNI. Il trasporto USB
   va adattato ai file descriptor autorizzati da `UsbManager`: Android non
   garantisce accesso diretto a `/dev/ttyUSB*` e non basta compilare Hamlib.
2. Driver CAT Kotlin/Java per un primo insieme esplicito di modelli, usando
   una libreria seriale Android e i comandi documentati dai produttori.

Prima di scegliere una dipendenza, fissare versione, licenza e primo modello
di radio/cavo da verificare. Una candidata per il solo trasporto è
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android), che
non fornisce da sola i protocolli CAT. Hamlib non è ancora inclusa.

Serviranno gestione attach/detach, richiesta del permesso per singolo
dispositivo, VID/PID, baud rate, timeout, capacità radio e rilascio PTT su
errore/disconnessione. Nessuna lista universale di radio compatibili è
dichiarata in questo scheletro.

## Audio e VOX

`AudioEngine` distingue microfono/altoparlante e USB audio. Implementare con
`AudioRecord`/`AudioTrack` o Oboe, selezione dispositivi, gestione audio focus e
permesso runtime `RECORD_AUDIO`. Il manifest dichiara il permesso ma la demo
non lo richiede, perché non registra.

Il PCM del decoder è a 12 kHz; l’hardware Android può lavorare a 48 kHz e
richiede ricampionamento. Definire in seguito buffer, timestamp, canali e
formato PCM. Nel percorso acustico è la radio a gestire il VOX; nell’app
saranno necessari livelli, coda audio e margine iniziale/finale.

Per una futura operatività in background progettare un foreground service
microfono, relativo ciclo di vita e permessi secondo il target Android.
Questa demo resta in primo piano e non avvia servizi.

## Motore FT8

`Ft8Engine` è soltanto il contratto iniziale per codifica/decodifica. Non
contiene un motore. Un futuro porting deve verificare i nominativi CB,
l’apprendimento hash, i messaggi type 4, i report e l’AutoSeq specifici di
WSJT-CB, oltre a CRC, LDPC, modulazione e sincronizzazione UTC degli slot.

`native/CMakeLists.txt` compila soltanto un simbolo C di stato: non viene
caricato dall’app. Attivare `externalNativeBuild` e fissare NDK/ABI solo
quando si introduce un backend reale. Il codice Fortran desktop non è
direttamente una libreria Android: valutare toolchain e porting senza
promettere un collegamento automatico.

## Collegamento desktop

L’app svolgerà il ruolo di server UDP per il `MessageClient` desktop. Il
bridge sarà separato dal motore FT8 locale e dai driver radio. Il trasporto
di rete dovrà produrre eventi di dominio per la UI, negoziare lo schema e
tenere traccia di istanza desktop, endpoint, scadenza heartbeat e stato.

Le impostazioni UI sono conservate soltanto nello stato Compose salvabile;
aggiungere DataStore quando sarà definito il modello definitivo. Non c’è
ancora un repository persistente né uno stato condiviso fra processi.
