package ru.familytv.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class MicPermissionGateTest {
    @Test
    fun proceedsWhenGranted() {
        assertEquals(
            MicPermissionAction.PROCEED,
            MicPermissionGate.nextAction(
                hasPermission = true,
                alreadyAskedThisSession = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun explainsOnFirstAsk() {
        assertEquals(
            MicPermissionAction.EXPLAIN_THEN_REQUEST,
            MicPermissionGate.nextAction(
                hasPermission = false,
                alreadyAskedThisSession = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun explainsWhenRationaleStillAllowed() {
        assertEquals(
            MicPermissionAction.EXPLAIN_THEN_REQUEST,
            MicPermissionGate.nextAction(
                hasPermission = false,
                alreadyAskedThisSession = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun unavailableWhenPermanentlyDenied() {
        assertEquals(
            MicPermissionAction.SHOW_UNAVAILABLE,
            MicPermissionGate.nextAction(
                hasPermission = false,
                alreadyAskedThisSession = true,
                shouldShowRationale = false,
            ),
        )
    }
}
