package com.airscreen.tv.core.session

import com.airscreen.tv.domain.model.PinVerification
import com.airscreen.tv.domain.model.SenderProtocol
import com.airscreen.tv.domain.model.SessionAdmission
import com.airscreen.tv.domain.model.SessionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerImplTest {

    @Test
    fun pinChallengeCanBeVerifiedAndCompleted() {
        val manager = SessionManagerImpl()
        val challenge = manager.requestPinChallenge()
        assertEquals(4, challenge.code.value.length)
        val wrong = PinVerification.Incorrect::class.java
        val result = manager.verifyPin(challenge.id, com.airscreen.tv.domain.model.PinCode("0000"))
        assertTrue(result is PinVerification.Incorrect || result is PinVerification.Accepted)
        manager.completePinChallenge(challenge.id)
        assertTrue(manager.pinChallenges.value.isEmpty())
    }

    @Test
    fun allocatesFourSessionsAndRejectsFifth() {
        val manager = SessionManagerImpl()
        val accepted = (0 until 4).map {
            manager.allocateSession(
                SessionRequest(
                    protocol = SenderProtocol.AIRPLAY,
                    senderName = "sender-" + it,
                    remoteAddress = "192.168.1." + (10 + it),
                    requiresVideo = true
                )
            )
        }
        assertTrue(accepted.all { it is SessionAdmission.Accepted })
        val fifth = manager.allocateSession(
            SessionRequest(SenderProtocol.AIRPLAY, "sender-5", "192.168.1.15", true)
        )
        assertTrue(fifth is SessionAdmission.Rejected)
    }

    @Test
    fun audioOnlySessionsDoNotConsumeSurfaceSlots() {
        val manager = SessionManagerImpl()
        val session = manager.allocateSession(
            SessionRequest(SenderProtocol.RAOP, "audio", "192.168.1.20", false)
        )
        assertTrue(session is SessionAdmission.Accepted)
        assertEquals(null, (session as SessionAdmission.Accepted).session.surfaceSlot)
    }
}
