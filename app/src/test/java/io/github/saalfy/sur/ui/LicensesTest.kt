package io.github.saalfy.sur.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** The About dialog license list must be complete and well-formed. */
class LicensesTest {

    @Test
    fun listIsNotEmpty() {
        assertTrue(openSourceLicenses.isNotEmpty())
    }

    @Test
    fun noBlankNameOrLicense() {
        for (license in openSourceLicenses) {
            assertTrue("blank name", license.name.isNotBlank())
            assertTrue("blank license for ${license.name}", license.license.isNotBlank())
        }
    }

    @Test
    fun namesAreUnique() {
        val names = openSourceLicenses.map { it.name }
        assertTrue("duplicate names: $names", names.size == names.toSet().size)
    }
}
