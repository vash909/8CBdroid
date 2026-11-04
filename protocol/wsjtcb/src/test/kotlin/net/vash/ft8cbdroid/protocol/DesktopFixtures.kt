package net.vash.ft8cbdroid.protocol

/** Independent big-endian fixtures generated with Python struct, following the pinned Qt wire format. */
internal object DesktopFixtures {
    val heartbeat = hex("adbccbda00000002000000000000000757534a542d43420000000300000005312e342e310000000739346637373664")
    val status = hex("adbccbda00000003000000010000000757534a542d43420000000001a007e8000000034654380000000732364154313031000000032d313200000003465438010001")
    val legacyHeartbeat = heartbeat.copyOf(23)
    val decode = hex("adbccbda00000003000000020000000757534a542d43420102f55fb2ffffffef3fd0000000000000000003e8000000017e0000000e435120314154313036204a4e34350100")
    val clear = heartbeat.copyOf(23).also { it[11] = 3 }
    val close = heartbeat.copyOf(23).also { it[11] = 6 }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
