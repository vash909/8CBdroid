package net.vash.ft8cbdroid.core.profile

import java.io.*
import net.vash.ft8cbdroid.core.ConnectionMode
import net.vash.ft8cbdroid.core.Ft8DecodeProfile

/** Versioned, bounded local storage. Unknown/corrupt data is reported, never replaced with defaults. */
object ProfileCodec {
    private const val MAGIC = 0x38434250
    private const val VERSION = 1
    private const val MAX_BYTES = 64 * 1024
    fun encode(value: ProfileCollection): ByteArray {
        value.validate()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(value.activeId); out.writeInt(value.profiles.size)
            value.profiles.forEach { p ->
                out.writeUTF(p.id); out.writeUTF(p.name); out.writeUTF(p.mode.name); out.writeLong(p.referenceFrequencyHz)
                out.writeBoolean(p.radio != null)
                p.radio?.let { out.writeInt(it.id); out.writeUTF(it.version); out.writeUTF(it.label) }
                out.writeInt(p.serial.baud); out.writeInt(p.serial.dataBits); out.writeInt(p.serial.stopBits)
                out.writeUTF(p.serial.parity.name); out.writeUTF(p.serial.flow.name)
                out.writeUTF(p.audioInput.name); out.writeUTF(p.audioOutput.name)
                out.writeInt(p.lan.port); out.writeUTF(p.lan.desktopAddress); out.writeUTF(p.decoder.name)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) { "Profile data is too large." } }
    }
    fun decode(bytes: ByteArray): ProfileCollection {
        require(bytes.size <= MAX_BYTES) { "Profile data is too large." }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid profile file." }
            require(input.readInt() == VERSION) { "Unsupported profile version. Use a compatible app version." }
            val active = input.readUTF(); val count = input.readInt()
            require(count in 1..32) { "Invalid profile count." }
            val profiles = List(count) {
                val id = input.readUTF(); val name = input.readUTF(); val mode = ConnectionMode.valueOf(input.readUTF()); val frequency = input.readLong()
                val radio = if (input.readBoolean()) HamlibModelRef(input.readInt(), input.readUTF(), input.readUTF()) else null
                val serial = SerialSettings(input.readInt(), input.readInt(), input.readInt(), SerialParity.valueOf(input.readUTF()), SerialFlow.valueOf(input.readUTF()))
                OperatingProfile(id, name, mode, frequency, radio, serial,
                    ProfileAudioRoute.valueOf(input.readUTF()), ProfileAudioRoute.valueOf(input.readUTF()),
                    ProfileLan(input.readInt(), input.readUTF()), Ft8DecodeProfile.valueOf(input.readUTF()))
            }
            require(input.available() == 0) { "Unexpected profile data." }
            ProfileCollection(profiles, active).validate()
        }
    }
    fun read(input: InputStream): ProfileCollection {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            require(count > 0) { "Could not read profile data." }
            output.write(buffer, 0, count)
            require(output.size() <= MAX_BYTES) { "Profile data is too large." }
        }
        return decode(output.toByteArray())
    }
}
