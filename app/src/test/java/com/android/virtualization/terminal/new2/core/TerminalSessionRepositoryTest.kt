package com.android.virtualization.terminal.new2.core

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class TerminalSessionRepositoryTest {
    @After fun reset() { TerminalSessionRepository.reset() }

    @Test fun closingLastSessionStaysEmptyUntilExplicitAdd() {
        TerminalSessionRepository.reset()
        val old = TerminalSessionRepository.selectedSessionId.value
        TerminalSessionRepository.removeSession(old)
        assertTrue(TerminalSessionRepository.sessions.value.isEmpty())
        assertEquals("", TerminalSessionRepository.selectedSessionId.value)
        // A late close event or stale selection must not resurrect a closed tab.
        TerminalSessionRepository.removeSession(old)
        TerminalSessionRepository.selectSession(old)
        assertTrue(TerminalSessionRepository.sessions.value.isEmpty())
        TerminalSessionRepository.addSession()
        val session = TerminalSessionRepository.sessions.value.single()
        assertNotEquals(old, session.id)
        assertEquals(session.id, TerminalSessionRepository.selectedSessionId.value)
    }
}
