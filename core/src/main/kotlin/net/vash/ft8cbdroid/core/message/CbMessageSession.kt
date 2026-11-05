package net.vash.ft8cbdroid.core.message

import java.util.Locale
import net.vash.ft8cbdroid.core.Ft8Payload77

enum class LearnResult { QUEUED, KNOWN, INVALID, CAPACITY_REACHED }

/**
 * Single-owner RX session; callers serialize access and supply integrity-checked frames.
 * Staging/commit and local-call orientation follow the pinned desktop reference.
 * Unlike desktop last-writer lookup, known collisions remain ambiguous. No TX state.
 */
class CbMessageSession(
    stationCall: String? = null,
    private val capacity: Int = 1000,
    private val pendingCapacity: Int = 200,
) {
    private val committed = linkedSetOf<String>()
    private val pending = linkedSetOf<String>()
    private val index = mutableMapOf<CallsignHash, MutableSet<String>>()
    var stationCall: String? = null
        private set
    var capacityReached = false
        private set
    val learnedCount: Int get() = committed.size
    val pendingCount: Int get() = pending.size

    init {
        require(capacity in 1..1000 && pendingCapacity in 1..200)
        reset(stationCall)
    }

    /** Queues an explicitly supplied full identity; never learns from a hash rendering. */
    fun learnCall(input: String): LearnResult {
        val call = normalizeCall(input) ?: return LearnResult.INVALID
        if (capacityReached) return LearnResult.CAPACITY_REACHED
        if (call in committed || call in pending || call == stationCall) return LearnResult.KNOWN
        if (pending.size >= pendingCapacity || committed.size + pending.size >= capacity) {
            // No eviction: dropping a collision candidate could falsely make a hash unique.
            capacityReached = true
            return LearnResult.CAPACITY_REACHED
        }
        pending += call
        return LearnResult.QUEUED
    }

    /** A batch becomes visible only here; a receive never resolves its own staged calls. */
    fun commitPending() {
        for (call in pending) {
            committed += call
            for (width in listOf(10, 12, 22))
                index.getOrPut(CbCallsignHash.calculate(call, width)) { linkedSetOf() } += call
        }
        pending.clear()
    }

    /** Direct desktop RX lookup: the local call is present in hash22, not hash10/12. */
    fun lookup(hash: CallsignHash): MessagePart.HashedCall = resolve(hash, includeStation = hash.width == 22)

    fun unpack(payload: Ft8Payload77): UnpackResult {
        val raw = CbMessageCodec.unpack(payload)
        if (raw !is UnpackResult.Decoded) return raw
        val message = raw.message
        val resolved = message.copy(parts = message.parts.mapIndexed { position, part ->
            if (part is MessagePart.UnresolvedCall) {
                val localDestination = message.format == MessageFormat.NONSTANDARD_CALL && position == 0
                resolve(part.hash, includeStation = part.hash.width == 22 || localDestination)
            } else part
        })
        // Learn only full call fields from supported, successfully unpacked messages.
        // Free text, reports, locators, CQ modifiers and resolved hashes cannot teach RX.
        val callFields = when (message.format) {
            MessageFormat.FREE_TEXT -> emptyList()
            MessageFormat.STANDARD, MessageFormat.STANDARD_PORTABLE -> message.parts.take(2)
            MessageFormat.NONSTANDARD_CALL -> message.parts.take(2)
        }
        callFields.filterIsInstance<MessagePart.Text>().forEach { learnCall(it.value) }
        return UnpackResult.Decoded(resolved)
    }

    /** A new station/session discards pending calls, learned calls and overflow state. */
    fun reset(stationCall: String? = null) {
        val normalized = stationCall?.let { requireNotNull(normalizeCall(it)) { "Invalid station callsign." } }
        committed.clear()
        pending.clear()
        index.clear()
        capacityReached = false
        this.stationCall = normalized
    }

    private fun resolve(hash: CallsignHash, includeStation: Boolean): MessagePart.HashedCall {
        if (capacityReached) return MessagePart.UnresolvedCall(hash, UnresolvedReason.CAPACITY_REACHED)
        val candidates = index[hash].orEmpty().toMutableSet()
        stationCall?.let { if (includeStation && CbCallsignHash.calculate(it, hash.width) == hash) candidates += it }
        return when (candidates.size) {
            0 -> MessagePart.UnresolvedCall(hash)
            1 -> MessagePart.ResolvedCall(hash, candidates.single())
            else -> MessagePart.AmbiguousCall(hash, candidates.toSortedSet().toSet())
        }
    }

    private fun normalizeCall(input: String): String? {
        if (input.any { it.code !in 32..126 }) return null
        val call = input.trim().uppercase(Locale.ROOT)
        return call.takeIf {
            it.length in 3..11 && it.all { c -> c in 'A'..'Z' || c in '0'..'9' || c == '/' } &&
                it.any { c -> c in 'A'..'Z' } && it.any { c -> c in '0'..'9' }
        }
    }
}
