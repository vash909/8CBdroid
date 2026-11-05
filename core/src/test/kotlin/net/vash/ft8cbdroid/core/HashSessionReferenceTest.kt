package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.reference.HashSessionReferenceChecks
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HashSessionReferenceTest(private val id: String, private val rows: List<List<String>>) {
    @Test fun matchesDesktopOrRequiresExplicitCollisionAmbiguity() = HashSessionReferenceChecks.verify(rows)

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val lines = requireNotNull(HashSessionReferenceTest::class.java.getResourceAsStream("/wsjtcb/hash-sessions.tsv"))
                .bufferedReader().use { it.readLines() }
            return HashSessionReferenceChecks.scenarios(lines).map { (id, rows) -> arrayOf(id, rows) }
        }
    }
}
