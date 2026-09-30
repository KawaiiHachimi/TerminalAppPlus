package com.android.virtualization.terminal.new2.core

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class TerminalSessionRepositoryTest : com.android.virtualization.terminal.LocalizedResourcesTest() {
    @After fun reset() { TerminalSessionRepository.reset() }

    @Test fun consoleStartupClearsPreviousTabsAndAllowsExplicitAdd() {
        TerminalSessionRepository.reset()
        TerminalSessionRepository.addSession()
        TerminalSessionRepository.reset(openInitialTab = false)
        assertTrue(TerminalSessionRepository.sessions.value.isEmpty())
        assertEquals("", TerminalSessionRepository.selectedSessionId.value)
        TerminalSessionRepository.addSession()
        assertEquals(1, TerminalSessionRepository.sessions.value.size)
        assertEquals(TerminalSessionRepository.sessions.value.single().id, TerminalSessionRepository.selectedSessionId.value)
        TerminalSessionRepository.reset(openInitialTab = true)
        assertEquals(1, TerminalSessionRepository.sessions.value.size)
    }

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
