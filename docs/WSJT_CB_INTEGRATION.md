# Integrazione WSJT-CB

Analisi fissata al commit
[`94f776d54946fe43db5e0bb9c966b1595c7b7d97`](https://github.com/vash909/WSJT-CB/tree/94f776d54946fe43db5e0bb9c966b1595c7b7d97).
Rivedere questo documento quando si aggiorna il riferimento.

## API di rete effettive

L’interfaccia rilevata nei sorgenti è il protocollo UDP binario derivato da
WSJT-X, non un’API HTTP/REST. I riferimenti sono:

- [NetworkMessage.hpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Network/NetworkMessage.hpp): formati, tipi e negoziazione.
- [NetworkMessage.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Network/NetworkMessage.cpp): serializzazione Qt.
- [MessageClient.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Network/MessageClient.cpp): socket, porte, heartbeat e comandi in ingresso.
- [Configuration.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Configuration.cpp): default `UDPServer=127.0.0.1`, `UDPServerPort=2237`.

Il desktop apre un socket con **porta sorgente effimera** e invia al server
configurato. Il telefono dovrà quindi ascoltare, per esempio su UDP 2237;
non deve presumere che il PC ascolti su 2237. Le risposte tornano all’IP e
alla porta sorgente del datagramma desktop, conservando il suo ID istanza.

## Configurazione prevista per una futura prova LAN

1. Collegare telefono e PC alla stessa LAN con comunicazione fra client
   consentita. Individuare l’IP Wi-Fi del telefono.
2. In WSJT-CB, nelle impostazioni UDP, sostituire il server `127.0.0.1` con
   l’IP del telefono e scegliere la stessa porta di ascolto.
3. Abilitare l’accettazione delle richieste UDP se si vogliono usare comandi.
4. Avviare il futuro listener Android, ricevere heartbeat e memorizzare
   endpoint e ID. Rispondere con heartbeat per negoziare lo schema.
5. Verificare prima `Status`, `Decode`, `LoggedADIF` e `Replay`; aggiungere i
   comandi soltanto dopo la verifica bidirezionale.

Questa sequenza è documentazione per lo sviluppo: il listener nella demo
non è implementato. Lo stesso Wi-Fi, da solo, non configura WSJT-CB.

## Formato sul filo

Big endian; header `uint32 magic=0xadbccbda`, `uint32 schema`, `uint32 type`,
ID istanza UTF-8 codificato come `QByteArray` (lunghezza `uint32` seguita dai
byte). Lunghezza zero e `0xffffffff` distinguono stringa vuota e nulla.

Schema iniziale 2 (`Qt_5_2`), massimo 3 (`Qt_5_4`); schema 1 escluso.
Heartbeat nominale ogni 15 secondi. Il suo payload moderno contiene massimo
schema, versione e revisione. Un peer più vecchio può omettere il massimo:
il ricevitore completo dovrà assumere 2. Il codec attuale genera soltanto
il formato moderno e legge soltanto l’intestazione.

I campi float Qt sono serializzati come double IEEE 754 a 64 bit. `QTime`
è espresso in millisecondi dalla mezzanotte; gestire `QDateTime` secondo lo
schema per i log. Ignorare tipi sconosciuti e campi aggiunti in coda.

| Tipo | ID | Direzione rispetto al desktop |
| --- | --- | --- |
| Heartbeat | 0 | entrambe |
| Status / Decode | 1 / 2 | desktop → telefono |
| Clear | 3 | entrambe |
| Reply | 4 | telefono → desktop |
| QSOLogged | 5 | desktop → telefono |
| Close | 6 | entrambe |
| Replay / HaltTx / FreeText | 7 / 8 / 9 | telefono → desktop |
| WSPRDecode | 10 | desktop → telefono |
| Location | 11 | telefono → desktop |
| LoggedADIF | 12 | desktop → telefono |
| HighlightCallsign / SwitchConfiguration | 13 / 14 | telefono → desktop |
| Configure / AnnotationInfo | 15 / 16 | telefono → desktop |

Non è un protocollo di streaming audio o un sostituto generale di CAT.
`Configure` non è un comando arbitrario di sintonia della frequenza dial.
Il controllo diretto radio richiede un backend separato. Per una futura
versione operativa verificare anche i permessi di accesso alla rete locale
richiesti dal target Android; le dichiarazioni attuali non costituiscono
un’implementazione del trasporto.

## Cosa è già presente

`WsjtCbProtocol` implementa lettura header, riconoscimento dei tipi e
generazione heartbeat offline. I test usano fixture binarie indipendenti,
incluse stringhe Qt nulle/vuote e pacchetti malformati. Non sono ancora
presenti socket, parsing payload, scoperta peer, negoziazione completa,
timer o comandi operativi.

## FT8 via radio

I parametri e la grammatica CB sono verificati in
[ft8_params.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/ft8_params.f90),
[cb_callsigns.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/cb_callsigns.f90)
e nel [README upstream](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/README.md).
La validazione del nominativo non garantisce che un messaggio sia codificabile.
Confrontare in futuro packing, hash, type 4 e sequenze CB con vettori di test
desktop, poi confrontare audio simulato in entrambe le direzioni.
