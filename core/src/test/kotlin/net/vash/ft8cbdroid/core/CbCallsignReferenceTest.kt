package net.vash.ft8cbdroid.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Expected values come from the unmodified pinned Fortran module, not this validator. */
@RunWith(Parameterized::class)
class CbCallsignReferenceTest(
    private val id: String,
    private val input: String,
    private val accepted: Boolean,
) {
    @Test fun agreesWithDesktopCompleteCallPredicate() {
        assertEquals("$id: '$input'", accepted, CbCallsign.isValid(input))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> {
            val stream = requireNotNull(CbCallsignReferenceTest::class.java.getResourceAsStream("/wsjtcb/predicates.tsv"))
            val rows = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .map { line ->
                    val fields = line.split('\t')
                    require(fields.size == 4 && fields[0] in listOf("C", "T") && fields[3] in listOf("0", "1"))
                    fields
                }
            require(rows.map { it[1] }.distinct().size == rows.size) { "Duplicate reference IDs" }
            val cases = rows.filter { it[0] == "C" }.map { fields ->
                val input = fields[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.US_ASCII)
                arrayOf<Any>(fields[1], input, fields[3] == "1")
            }
            require(cases.size >= 200) { "Incomplete desktop callsign fixture" }
            return cases
        }
    }
}
