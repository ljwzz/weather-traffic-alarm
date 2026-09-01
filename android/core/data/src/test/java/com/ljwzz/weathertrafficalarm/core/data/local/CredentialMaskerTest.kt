package com.ljwzz.weathertrafficalarm.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialMaskerTest {

    @Test
    fun neverReturnsSecretValueForConfiguredCredential() {
        assertEquals("ab••••yz", CredentialMasker.mask("abcdefyz"))
        assertEquals("a••d", CredentialMasker.mask("abcd"))
        assertEquals("•", CredentialMasker.mask("a"))
        assertNull(CredentialMasker.mask(null))
        assertNull(CredentialMasker.mask(""))
    }
}
