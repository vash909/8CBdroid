package net.vash.ft8cbdroid.ui

/** Presentation routes only. Receiver ownership remains in the existing ViewModels. */
enum class AppDestination(val title: String) {
    OPERATE("Operate"), SETTINGS("Settings"), CHAT("Chat"),
    PROFILES("Operating profiles"), STATION("Station"), AUDIO("Audio & source"), TIME("Time synchronization"),
    LAN("WSJT-CB LAN"), USB("USB / CAT"), CAPTURE("Microphone tools"),
    MESSAGES("Message tools"), RECORDINGS("Recorded audio"), LOGBOOK("Logbook");
    val showsReceiver get() = this == OPERATE || this == CAPTURE
    val tab get() = when (this) { OPERATE -> OPERATE; CHAT -> CHAT; else -> SETTINGS }
}

enum class OperatingSource(val label: String) { LOCAL("Phone / USB audio"), LAN("WSJT-CB LAN") }

fun stopsLocalReception(from: AppDestination, to: AppDestination) = from != to && !to.showsReceiver

/** Invalid edits never change a spectrum cursor or become a transmit frequency. */
fun audioOffset(text: String): Int? = text.toIntOrNull()?.takeIf { it in 200..2950 }
