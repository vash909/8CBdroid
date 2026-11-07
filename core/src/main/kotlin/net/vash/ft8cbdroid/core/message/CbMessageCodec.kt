package net.vash.ft8cbdroid.core.message

import java.math.BigInteger
import java.util.Locale
import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.Ft8Payload77

// GPL-3.0-only. Port of selected packjt77/packjt77var routines from the pinned
// WSJT-CB reference. Source locations and deliberate limits: docs/CB_MESSAGE_CODEC.md.
enum class MessageFormat { FREE_TEXT, STANDARD, STANDARD_PORTABLE, NONSTANDARD_CALL }
enum class EncodingMode { AUTOMATIC, FREE_TEXT }

data class CallsignHash(val width: Int, val value: Int) {
    init { require(width in setOf(10, 12, 22) && value in 0 until (1 shl width)) }
}

sealed interface MessagePart {
    data class Text(val value: String) : MessagePart
    sealed interface HashedCall : MessagePart { val hash: CallsignHash }
    /** A channel hash is not a resolved callsign or evidence of a QSO peer. */
    data class UnresolvedCall(
        override val hash: CallsignHash,
        val reason: UnresolvedReason = UnresolvedReason.UNKNOWN,
    ) : HashedCall
    /** One known candidate, not proof of identity or permission to advance a QSO. */
    data class ResolvedCall(override val hash: CallsignHash, val callsign: String) : HashedCall
    data class AmbiguousCall(override val hash: CallsignHash, val candidates: Set<String>) : HashedCall
}

enum class UnresolvedReason { UNKNOWN, CAPACITY_REACHED }

data class DecodedMessage(val format: MessageFormat, val parts: List<MessagePart>) {
    val text: String get() = parts.joinToString(" ") {
        when (it) {
            is MessagePart.Text -> it.value
            is MessagePart.UnresolvedCall -> "<...>"
            is MessagePart.ResolvedCall -> "<${it.callsign}>"
            is MessagePart.AmbiguousCall -> "<...>"
        }
    }
}

sealed interface UnpackResult {
    data class Decoded(val message: DecodedMessage) : UnpackResult
    data class Unsupported(val reason: String) : UnpackResult
}

sealed interface PackResult {
    data class Encoded(
        val normalizedInput: String,
        val payload: Ft8Payload77,
        val message: DecodedMessage,
        /** Rendered with explicitly supplied TX identities only; no learned hash cache. */
        val transmittedText: String,
        val contentChanged: Boolean,
    ) : PackResult
    data class Unsupported(val reason: String) : PackResult
}

object CbCallsignHash {
    fun calculate(call: String, width: Int): CallsignHash {
        require(width in setOf(10, 12, 22))
        require(call.length in 3..11 && call.all { it in CALL_ALPHABET && it != ' ' })
        var value = 0L
        for (c in call.padEnd(11)) value = value * 38 + CALL_ALPHABET.indexOf(c)
        // The desktop's signed 64-bit multiply intentionally wraps, then shifts logically.
        return CallsignHash(width, ((value * 47_055_833_459L) ushr (64 - width)).toInt())
    }
}

private const val CALL_ALPHABET = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/"
private const val TEXT_ALPHABET = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
private const val TOKENS = 2_063_592L
private const val HASH22 = 4_194_304L
private val grid = Regex("[A-R]{2}[0-9]{2}")
private val report = Regex("R?[+-][0-9]{1,3}")
private val special = setOf("CQ", "DE", "QRZ")
private val finals = listOf("", "RRR", "RR73", "73")

/** Stateless 77-bit message codec. No DSP, radio access, RX learning or AutoSeq. */
object CbMessageCodec {
    fun pack(input: String, mode: EncodingMode = EncodingMode.AUTOMATIC): PackResult {
        if (input.length > 37 || input.any { it.code !in 32..126 })
            return PackResult.Unsupported("Use at most 37 printable ASCII characters.")
        val normalized = input.trim().replace(Regex(" +"), " ").uppercase(Locale.ROOT)
        if (normalized.isEmpty()) return PackResult.Unsupported("Enter a message.")
        if ("<...>" in normalized) return PackResult.Unsupported("An unresolved callsign cannot be encoded for TX.")
        return try {
            val words = normalized.split(' ')
            val known = words.filter { it.startsWith('<') && it.endsWith('>') }.map { it.drop(1).dropLast(1) }
            require(known.all { it.length in 3..11 && it.all { c -> c in CALL_ALPHABET && c != ' ' } }) {
                "Hashed calls must contain 3–11 letters, digits or slash characters."
            }
            if (mode == EncodingMode.AUTOMATIC && words.size >= 4 &&
                (standardBase(words[0]) != null || hashed(words[0])) &&
                (standardBase(words[1]) != null || hashed(words[1]))) {
                require(words.size == 4 && words[2] == "R" && grid.matches(words[3].take(4))) {
                    "Contest exchanges are not supported."
                }
            }
            val bits = if (mode == EncodingMode.FREE_TEXT) freeText(normalized) else {
                require(!normalized.contains(';') && !normalized.startsWith('$')) { "Contest and macro encodings are not supported." }
                require(!(words.size == 1 && normalized.matches(Regex("[0-9A-F]{1,18}")))) {
                    "Telemetry is not supported; select Free text to encode this text explicitly."
                }
                standard(words) ?: nonstandard(words) ?: freeText(normalized)
            }
            val payload = Ft8Payload77.fromBits(bits)
            when (val decoded = unpack(payload)) {
                is UnpackResult.Unsupported -> PackResult.Unsupported(decoded.reason)
                is UnpackResult.Decoded -> {
                    val preview = decoded.message.parts.joinToString(" ") { part ->
                        when (part) {
                            is MessagePart.Text -> part.value
                            is MessagePart.UnresolvedCall -> {
                                val matches = known.filter { CbCallsignHash.calculate(it, part.hash.width) == part.hash }.distinct()
                                require(matches.size == 1) { "The supplied TX identities are ambiguous." }
                                "<${matches.single()}>"
                            }
                            is MessagePart.ResolvedCall -> error("Stateless unpack returned a learned call")
                            is MessagePart.AmbiguousCall -> error("Stateless unpack returned an ambiguous call")
                        }
                    }
                    PackResult.Encoded(normalized, payload, decoded.message, preview, preview != normalized)
                }
            }
        } catch (error: IllegalArgumentException) {
            PackResult.Unsupported(error.message ?: "This message cannot be encoded.")
        }
    }

    fun unpack(payload: Ft8Payload77): UnpackResult = try {
        val bits = payload.toBitString()
        val type = number(bits, 74, 3).toInt()
        val message = when (type) {
            0 -> {
                require(number(bits, 71, 3) == 0L) { "This message subtype is not supported." }
                val value = BigInteger(bits.take(71), 2)
                require(value < BigInteger.valueOf(42).pow(13)) { "Invalid free-text payload." }
                val text = radixText(value, TEXT_ALPHABET, 13).trim()
                require(text.isNotEmpty()) { "Empty free-text payload." }
                DecodedMessage(MessageFormat.FREE_TEXT, listOf(MessagePart.Text(text)))
            }
            1, 2 -> unpackStandard(bits, type)
            4 -> unpackNonstandard(bits)
            else -> throw IllegalArgumentException("This message type is not supported.")
        }
        UnpackResult.Decoded(message)
    } catch (error: IllegalArgumentException) {
        UnpackResult.Unsupported(error.message ?: "Invalid message payload.")
    }

    private fun freeText(text: String): String {
        require(text.all { it in TEXT_ALPHABET }) { "This text contains characters outside the FT8 free-text alphabet." }
        val value = text.take(13).trimEnd().fold(BigInteger.ZERO) { n, c ->
            n * BigInteger.valueOf(42) + BigInteger.valueOf(TEXT_ALPHABET.indexOf(c).toLong())
        }
        return value.toString(2).padStart(71, '0') + "000000"
    }

    private fun standard(original: List<String>): String? {
        var words = original
        // split77 joins a CQ modifier only when the following word is a standard call.
        if (words.size >= 3 && words[0] == "CQ" && standardBase(words[2]) != null)
            words = listOf("CQ_${words[1]}") + words.drop(2)
        if (words.size !in 2..4) return null
        fun valid(call: String) = call in special || call.startsWith("CQ_") || hashed(call) || standardBase(call) != null
        if (!valid(words[0]) || !valid(words[1]) || words[1] in special || words[1].startsWith("CQ_")) return null
        if ((hashed(words[0]) && '/' in words[1]) || (hashed(words[1]) && '/' in words[0])) return null
        if (words.size == 2 && '/' in words[1]) return null
        if (words.size == 4 && words[2] != "R") return null
        val last = words.last()
        var ir = 0
        val field = when {
            words.size == 2 -> 32401
            grid.matches(last.take(4)) -> {
                if (words.size == 4) ir = 1
                (last[0] - 'A') * 1800 + (last[1] - 'A') * 100 + (last[2] - '0') * 10 + (last[3] - '0')
            }
            report.matches(last) -> {
                ir = if (last.startsWith('R')) 1 else 0
                var value = last.removePrefix("R").toInt()
                require(value in -50..99) { "Structured reports must be between -50 and +99." }
                if (value in -50..-31) value += 101
                32400 + value + 35
            }
            last in finals.drop(1) -> 32400 + finals.indexOf(last) + 1
            else -> return null
        }
        val type = if (words.take(2).any { it.endsWith("/P") }) 2 else 1
        fun fieldCall(call: String): String {
            val suffix = if (call.endsWith("/P") || call.endsWith("/R")) 1 else 0
            return binary(pack28(call), 28) + suffix
        }
        return fieldCall(words[0]) + fieldCall(words[1]) + ir + binary(field.toLong(), 15) + binary(type.toLong(), 3)
    }

    private fun nonstandard(words: List<String>): String? {
        if (words.size !in 2..3) return null
        val cq = words[0] == "CQ"
        val flip = !cq && hashed(words[1])
        if (!cq && !hashed(words[0]) && !flip) return null
        if (cq && words[1].length <= 4) return null
        val full = if (flip) words[0] else words[1]
        require(full.length in 3..11 && full.all { it in CALL_ALPHABET && it != ' ' }) { "The full callsign cannot be encoded in type 4." }
        val hashCall = if (cq) full else (if (flip) words[1] else words[0]).drop(1).dropLast(1)
        val hash = CbCallsignHash.calculate(hashCall, 12)
        val value = full.fold(0L) { n, c -> n * 38 + CALL_ALPHABET.indexOf(c) }
        val final = if (cq || words.size == 2) 0 else finals.indexOf(words[2]).coerceAtLeast(0)
        return binary(hash.value.toLong(), 12) + binary(value, 58) + (if (flip) "1" else "0") +
            binary(final.toLong(), 2) + (if (cq) "1" else "0") + "100"
    }

    private fun pack28(call: String): Long {
        if (call in special) return specialTokens.indexOf(call).toLong()
        if (call.startsWith("CQ_")) {
            val modifier = call.drop(3)
            if (modifier.matches(Regex("[0-9]{3}"))) return 3L + modifier.toInt()
            require(modifier.matches(Regex("[A-Z]{1,4}"))) { "Unsupported CQ modifier." }
            return 1003 + modifier.padStart(4).fold(0L) { n, c -> n * 27 + " ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(c) }
        }
        if (hashed(call)) return TOKENS + CbCallsignHash.calculate(call.drop(1).dropLast(1), 22).value
        val base = requireNotNull(standardBase(call)) { "Unsupported standard callsign." }
        val padded = (if (base[1].isDigit()) " $base" else base).padEnd(6)
        var value = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(padded[0]).toLong()
        value = value * 36 + "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(padded[1])
        value = value * 10 + (padded[2] - '0')
        for (c in padded.drop(3)) value = value * 27 + " ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(c)
        return TOKENS + HASH22 + value
    }

    private fun unpackStandard(bits: String, type: Int): DecodedMessage {
        val parts = mutableListOf<MessagePart>()
        for (offset in listOf(0, 29)) {
            val part = unpack28(number(bits, offset, 28))
            val suffix = number(bits, offset + 28, 1) == 1L
            parts += if (suffix && part is MessagePart.Text && standardBase(part.value) != null)
                MessagePart.Text(part.value + if (type == 1) "/R" else "/P") else part
        }
        val ir = number(bits, 58, 1) == 1L
        val field = number(bits, 59, 15).toInt()
        val cq = (parts[0] as? MessagePart.Text)?.value.let { it == "CQ" || it?.startsWith("CQ ") == true }
        if (field < 32400) {
            require(!(parts[0] is MessagePart.UnresolvedCall && !ir && field != 32373 &&
                (parts[1] as? MessagePart.Text)?.value?.endsWith("/R") == true)) {
                "The desktop rejects this hashed grid message."
            }
            require(!cq || !ir) { "CQ cannot carry a roger flag." }
            if (ir) parts += MessagePart.Text("R")
            parts += MessagePart.Text("${'A' + field / 1800}${'A' + field / 100 % 18}${field / 10 % 10}${field % 10}")
        } else {
            val value = field - 32400
            require(value != 0 && (!cq || value == 1)) { "Invalid standard message field." }
            if (value in 2..4) parts += MessagePart.Text(finals[value - 1])
            else if (value >= 5) {
                var snr = value - 35
                if (snr > 50) snr -= 101
                require(snr in -50..50) { "Invalid report field." }
                val text = (if (snr >= 0) "+" else "-") + kotlin.math.abs(snr).toString().padStart(2, '0')
                parts += MessagePart.Text((if (ir) "R" else "") + text)
            }
        }
        return DecodedMessage(if (type == 1) MessageFormat.STANDARD else MessageFormat.STANDARD_PORTABLE, parts.toList())
    }

    private fun unpackNonstandard(bits: String): DecodedMessage {
        val hash = CallsignHash(12, number(bits, 0, 12).toInt())
        val value = number(bits, 12, 58)
        require(BigInteger.valueOf(value) < BigInteger.valueOf(38).pow(11)) { "Invalid type-4 callsign field." }
        val full = radixText(BigInteger.valueOf(value), CALL_ALPHABET, 11).trimStart()
        require(full.isNotEmpty() && ' ' !in full) { "Invalid full callsign field." }
        // Preserve the CB fork's complete-call exception for numeric-prefix identities.
        if (full.take(2).all { it in '0'..'9' } && full.length >= 2)
            require(CbCallsign.isValid(full)) { "The desktop rejects this CB callsign payload." }
        val flip = number(bits, 70, 1) == 1L
        val final = number(bits, 71, 2).toInt()
        val cq = number(bits, 73, 1) == 1L
        if (full.length == 11) {
            val slash = full.indexOf('/') + 1
            require(slash !in setOf(1, 2, 11)) { "Invalid slash position in full callsign." }
            require(!(slash == 10 && full[10] in 'A'..'Z' && full[10] != 'P')) { "Invalid callsign suffix." }
            require(!(slash < 6 && full[10].isDigit())) { "Invalid long callsign." }
        }
        if (cq) {
            require(!flip) { "CQ cannot contain an unresolved source call." }
            return DecodedMessage(MessageFormat.NONSTANDARD_CALL, listOf(MessagePart.Text("CQ"), MessagePart.Text(full)))
        }
        if (flip && final == 0 && !(full.endsWith("/P") && full.length <= 8))
            require(CbCallsign.isValid(full)) { "Unsupported trailing-hash message." }
        if (!flip && final == 0 && full.length > 9)
            require(!full.startsWith('/') && !full.endsWith('/')) { "Invalid long callsign." }
        val call = MessagePart.Text(full)
        val unresolved = MessagePart.UnresolvedCall(hash)
        val parts = if (flip) mutableListOf<MessagePart>(call, unresolved) else mutableListOf(unresolved, call)
        if (final > 0) parts += MessagePart.Text(finals[final])
        return DecodedMessage(MessageFormat.NONSTANDARD_CALL, parts.toList())
    }

    private fun unpack28(value: Long): MessagePart {
        if (value <= 2) return MessagePart.Text(specialTokens[value.toInt()])
        if (value <= 1002) return MessagePart.Text("CQ ${(value - 3).toString().padStart(3, '0')}")
        if (value <= 532443) return MessagePart.Text("CQ " + radixText(BigInteger.valueOf(value - 1003), " ABCDEFGHIJKLMNOPQRSTUVWXYZ", 4).trimStart())
        require(value >= TOKENS) { "Unassigned callsign token." }
        if (value < TOKENS + HASH22) return MessagePart.UnresolvedCall(CallsignHash(22, (value - TOKENS).toInt()))
        var n = value - TOKENS - HASH22
        val alphabets = listOf(" 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", "0123456789", " ABCDEFGHIJKLMNOPQRSTUVWXYZ", " ABCDEFGHIJKLMNOPQRSTUVWXYZ", " ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val chars = CharArray(6)
        for (i in 5 downTo 0) {
            chars[i] = alphabets[i][(n % alphabets[i].length).toInt()]
            n /= alphabets[i].length
        }
        val call = chars.concatToString().trim()
        require(n == 0L && standardBase(call) == call) { "Invalid standard callsign field." }
        return MessagePart.Text(call)
    }

    private val specialTokens = listOf("DE", "QRZ", "CQ")
    private fun hashed(call: String) = call.startsWith('<') && call.endsWith('>') && call.length >= 5
    private fun standardBase(call: String): String? {
        if (call.length !in 3..11 || call.any { it !in CALL_ALPHABET || it == ' ' }) return null
        val sides = call.split('/')
        if (sides.size > 2 || sides.any { it.isEmpty() }) return null
        val base = if (sides.size == 1) call else if (sides[0].length > sides[1].length) sides[0] else sides[1]
        if (base.length !in 3..6 || (base[0] == 'Q' && base != "QU1RK")) return null
        if (base.take(2).none { it in 'A'..'Z' }) return null
        val area = if (base[2] in '0'..'9') 2 else if (base[1] in '0'..'9') 1 else return null
        return base.takeIf { base.drop(area + 1).length in 1..3 && base.drop(area + 1).all { it in 'A'..'Z' } }
    }
    private fun binary(value: Long, size: Int): String {
        require(value >= 0 && value < (1L shl size)) { "Value does not fit the message field." }
        return value.toString(2).padStart(size, '0')
    }
    private fun number(bits: String, offset: Int, count: Int) = bits.substring(offset, offset + count).toLong(2)
    private fun radixText(value: BigInteger, alphabet: String, size: Int): String {
        var remaining = value
        val radix = BigInteger.valueOf(alphabet.length.toLong())
        val chars = CharArray(size)
        for (i in size - 1 downTo 0) {
            val qr = remaining.divideAndRemainder(radix)
            chars[i] = alphabet[qr[1].toInt()]
            remaining = qr[0]
        }
        return chars.concatToString()
    }
}
