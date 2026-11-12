package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.context.CbCountries
import org.junit.Assert.*
import org.junit.Test

class CbCountriesReferenceTest {
    @Test fun allPinnedDivisionsAndInvalidPrefixesMatchOriginalDesktopLookup() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/wsjtcb/country-cases.tsv")).bufferedReader().use { it.readLines() }
        var count = 0
        for (line in lines.filter { !it.startsWith('#') }) {
            val fields = line.split('\t'); assertEquals(3, fields.size)
            val input = fields[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
            val value = CbCountries.lookup(input)
            assertEquals(input, fields[1].ifEmpty { null }, value?.prefix)
            assertEquals(input, fields[2].ifEmpty { null }, value?.desktopName)
            count++
        }
        assertTrue(count >= 1200)
    }
    @Test fun englishCountryNamesPreserveTheOriginalDatasetSeparately() {
        assertEquals("Sardegna", CbCountries.lookup("165AT101")?.desktopName)
        assertEquals("Sardinia", CbCountries.lookup("165AT101")?.countryName)
        assertEquals("Palestine", CbCountries.lookup("340AT101")?.countryName)
        assertEquals("007", CbCountries.lookup("7AT1")?.prefix)
        assertNull(CbCountries.lookup("7AT1")?.countryName)
        assertNull(CbCountries.lookup("295AT1")?.countryName)
        assertNull(CbCountries.lookup("<26AT101>"))
        assertEquals("026", CbCountries.lookup("26AT")?.prefix)
        assertFalse(CbCallsign.isValid("26AT"))
    }
}
