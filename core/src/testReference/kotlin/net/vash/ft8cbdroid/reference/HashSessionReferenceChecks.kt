package net.vash.ft8cbdroid.reference

import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.message.*

/** Test-only adapter shared by JVM tests and the separate phone probe, never the app. */
object HashSessionReferenceChecks {
    fun scenarios(lines: List<String>): Map<String, List<List<String>>> = lines
        .filter { it.isNotEmpty() && !it.startsWith('#') }.map { it.split('\t') }.groupBy { it[0] }

    fun verify(rows: List<List<String>>) {
        val session = CbMessageSession()
        val id = rows.first()[0]
        // The original desktop overwrites collisions; Android deliberately retains both.
        val collisionCalls = when (id) {
            "collision-10-last-learned", "collision-12-last-learned" -> setOf("1AT0002", "1AT0050")
            "collision-22-last-learned" -> setOf("5AT179", "10AT107")
            else -> emptySet()
        }
        var collisionCommitted = false
        var secondCallQueued = false
        for (row in rows) {
            val argument = ascii(row[3])
            val expected = ascii(row[6])
            val label = "$id step ${row[1]}"
            when (row[2]) {
                "learn" -> {
                    check(session.learnCall(argument) in setOf(LearnResult.QUEUED, LearnResult.KNOWN)) { label }
                    if (argument == collisionCalls.lastOrNull()) secondCallQueued = true
                }
                "commit" -> {
                    session.commitPending()
                    if (secondCallQueued) collisionCommitted = true
                }
                "station" -> session.reset(argument)
                "lookup" -> {
                    val result = session.lookup(CbCallsignHash.calculate(argument, row[4].toInt()))
                    if (collisionCommitted) {
                        check(result is MessagePart.AmbiguousCall && result.candidates == collisionCalls) { label }
                        check(expected.removeSurrounding("<", ">") in result.candidates) { label }
                    } else check(render(result) == expected) { "$label: ${render(result)} != $expected" }
                }
                "decode", "raw" -> {
                    val payload = runCatching { Ft8Payload77.fromBits(argument) }.getOrNull()
                    if (payload == null) {
                        check(row[5] == "0") { label }
                        continue
                    }
                    val result = session.unpack(payload)
                    if (row[5] == "0") {
                        check(result is UnpackResult.Unsupported) { label }
                    } else {
                        check(result is UnpackResult.Decoded) { label }
                        if (collisionCommitted) {
                            val ambiguous = result.message.parts.filterIsInstance<MessagePart.AmbiguousCall>().single()
                            check(ambiguous.candidates == collisionCalls) { label }
                            val desktopWinner = expected.substringAfter('<').substringBefore('>')
                            check(desktopWinner in ambiguous.candidates) { label }
                            check(result.message.text == expected.replace("<$desktopWinner>", "<...>")) { label }
                        } else check(result.message.text == expected) { "$label: ${result.message.text} != $expected" }
                    }
                }
                else -> error("Unknown reference operation: ${row[2]}")
            }
        }
    }

    private fun render(part: MessagePart) = DecodedMessage(MessageFormat.NONSTANDARD_CALL, listOf(part)).text
    private fun ascii(hex: String) = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
}
