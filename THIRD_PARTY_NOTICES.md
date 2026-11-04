# Attribuzioni

8CBdroid è un nuovo scheletro Android. Non include ancora il motore DSP
desktop, Hamlib o una libreria seriale USB.

- [WSJT-CB](https://github.com/vash909/WSJT-CB), derivato da WSJT-X di Joe
  Taylor K1JT e collaboratori, modificato da Vash / 1AT106 / 1XZ001 e con i
  crediti riportati upstream. Protocollo UDP, parametri FT8 e grammatica CB
  sono stati usati come riferimento al commit indicato nella documentazione.
  Upstream distribuito sotto GPLv3. Lo scheletro adotta GPL-3.0-only.
- Gradle Wrapper e Gradle: Apache-2.0. Gli script e il JAR del wrapper
  provengono dal tag ufficiale `v8.13.0`.
- Kotlin e AndroidX / Jetpack Compose: Apache-2.0.
- JUnit 4: EPL-1.0.
- I pacchetti JDK/SDK scaricati per lo sviluppo conservano le rispettive
  licenze e non sono inclusi nel repository o nell’APK.

Per le dipendenze future mantenere un inventario di versioni e licenze,
incluse le eventuali librerie native e le loro dipendenze transitive.
