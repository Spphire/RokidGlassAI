package com.example.rokidphone.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneCompanionBridgeTest {
    @Test
    fun `glasses candidate matcher accepts rokid and glasses names`() {
        assertThat(PhoneCompanionBridge.matchesGlassesCandidateName("Rokid Glasses")).isTrue()
        assertThat(PhoneCompanionBridge.matchesGlassesCandidateName("Glasses_1484")).isTrue()
        assertThat(PhoneCompanionBridge.matchesGlassesCandidateName("RG_glasses")).isTrue()
    }

    @Test
    fun `glasses candidate matcher rejects unrelated phone names`() {
        assertThat(PhoneCompanionBridge.matchesGlassesCandidateName("iQOO V2408A")).isFalse()
        assertThat(PhoneCompanionBridge.matchesGlassesCandidateName("iPhone")).isFalse()
    }

    @Test
    fun `companion candidate keeps display name and bluetooth address`() {
        val candidate = CompanionCandidate(
            name = "Glasses_1484",
            address = "AC:86:D1:59:BD:43"
        )

        assertThat(candidate.name).isEqualTo("Glasses_1484")
        assertThat(candidate.address).isEqualTo("AC:86:D1:59:BD:43")
    }
}
