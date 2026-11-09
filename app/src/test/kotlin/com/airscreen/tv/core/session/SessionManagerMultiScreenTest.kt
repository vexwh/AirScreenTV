package com.airscreen.tv.core.session

import com.airscreen.tv.domain.model.AspectRatioMode
import com.airscreen.tv.domain.model.LayoutMode
import com.airscreen.tv.domain.model.SenderProtocol
import com.airscreen.tv.domain.model.SessionAdmission
import com.airscreen.tv.domain.model.SessionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerMultiScreenTest {

    @Test
    fun fourSessionsDriveLayoutAndControls() {
        val manager = SessionManagerImpl()
        val sessions = (0 until 4).map { index ->
            val admission = manager.allocateSession(
                SessionRequest(
                    protocol = SenderProtocol.AIRPLAY,
                    senderName = "sender-$index",
                    remoteAddress = "192.168.1." + (10 + index),
                    requiresVideo = true
                )
            )
            assertTrue(admission is SessionAdmission.Accepted)
            (admission as SessionAdmission.Accepted).session
        }

        assertEquals(4, manager.sessions.value.size)
        assertEquals(LayoutMode.QUAD_2X2, manager.multiScreenState.value.layoutMode)
        val first = sessions.first()
        manager.rotateSession(first.id.value)
        manager.cycleAspectRatio(first.id.value)
        manager.setAudioMuted(first.id.value, true)
        val updated = manager.sessions.value.first { it.id == first.id }
        assertEquals(90, updated.rotationDegrees)
        assertEquals(AspectRatioMode.FILL, updated.aspectRatioMode)
        assertTrue(updated.audioMuted)

        manager.setActiveAudioSession(updated.id.value)
        assertEquals(updated.id.value, manager.multiScreenState.value.activeAudioSessionId)

        manager.kickSession(sessions[1].id.value)
        assertEquals(3, manager.sessions.value.size)
        assertEquals(LayoutMode.QUAD_2X2, manager.multiScreenState.value.layoutMode)

        manager.releaseSession(sessions[2].id)
        assertEquals(2, manager.sessions.value.size)
        assertEquals(LayoutMode.SPLIT_1X2, manager.multiScreenState.value.layoutMode)

        manager.releaseSession(sessions[3].id)
        assertEquals(LayoutMode.SINGLE_1X1, manager.multiScreenState.value.layoutMode)

        manager.releaseSession(first.id)
        assertEquals(LayoutMode.IDLE, manager.multiScreenState.value.layoutMode)

        val one = manager.allocateSession(
            SessionRequest(SenderProtocol.AIRPLAY, "one", "192.168.1.30", true)
        ) as SessionAdmission.Accepted
        manager.allocateSession(
            SessionRequest(SenderProtocol.AIRPLAY, "two", "192.168.1.31", true)
        )
        manager.togglePip()
        assertEquals(LayoutMode.PIP, manager.multiScreenState.value.layoutMode)
        manager.releaseSession(one.session.id)
    }
}
