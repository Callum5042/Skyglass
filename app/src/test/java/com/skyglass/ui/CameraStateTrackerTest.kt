package com.skyglass.ui

import com.skyglass.ui.CameraErrorKind.CRITICAL
import com.skyglass.ui.CameraErrorKind.NONE
import com.skyglass.ui.CameraErrorKind.RECOVERABLE
import com.skyglass.ui.CameraPhase.CLOSED
import com.skyglass.ui.CameraPhase.OPEN
import com.skyglass.ui.CameraPhase.OPENING
import com.skyglass.ui.CameraPhase.PENDING_OPEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStateTrackerTest {

    private fun attachedTracker() = CameraStateTracker().also { it.attached() }

    @Test
    fun cameraInUse_waitsWithoutLeavingCameraMode_untilItOpens() {
        val t = attachedTracker()
        assertEquals(CameraVerdict.OK, t.onState(PENDING_OPEN, NONE))
        assertEquals(CameraVerdict.WAITING, t.onState(OPENING, RECOVERABLE))
        assertEquals(CameraVerdict.WAITING, t.onState(PENDING_OPEN, RECOVERABLE))
        // CameraX can publish error-free states between retries; the hint must not flicker off.
        assertEquals(CameraVerdict.WAITING, t.onState(OPENING, NONE))
        assertEquals(CameraVerdict.WAITING, t.onState(PENDING_OPEN, NONE))
        assertEquals(CameraVerdict.OK, t.onState(OPEN, NONE))
        // And once open, the hint stays off.
        assertEquals(CameraVerdict.OK, t.onState(OPENING, NONE))
    }

    @Test
    fun criticalError_isFatal_evenWhileWaiting() {
        val t = attachedTracker()
        t.onState(CLOSED, NONE)
        t.onState(OPENING, NONE)
        assertEquals(CameraVerdict.FATAL, t.onState(CLOSED, CRITICAL))
        val w = attachedTracker()
        w.onState(OPENING, RECOVERABLE)
        assertEquals(CameraVerdict.FATAL, w.onState(CLOSED, CRITICAL))
        assertEquals(CameraVerdict.OK, w.onState(CLOSED, NONE))
    }

    @Test
    fun staleValuesDeliveredWhileAttaching_areIgnored_butAFreshErrorRightAfterIsNot() {
        val t = CameraStateTracker()
        // Delivered synchronously inside observeForever: the last session's CLOSED + CAMERA_DISABLED (twice, via the mediator).
        assertEquals(CameraVerdict.OK, t.onState(CLOSED, CRITICAL))
        assertEquals(CameraVerdict.OK, t.onState(CLOSED, CRITICAL))
        assertEquals(CameraVerdict.OK, t.onState(CLOSED, RECOVERABLE))
        t.attached()
        // postValue may coalesce the new session's clean OPENING and its error into one delivery.
        assertEquals(CameraVerdict.FATAL, t.onState(CLOSED, CRITICAL))
    }

    @Test
    fun recoverableErrorWhileClosed_isNotAWaitingHint() {
        val t = attachedTracker()
        t.onState(OPEN, NONE)
        assertEquals(CameraVerdict.OK, t.onState(CLOSED, RECOVERABLE))
    }

    @Test
    fun permissionIsBlocked_onlyWhenTheDialogCouldHaveBeenRefused() {
        // First ever request, answered "deny": rationale becomes true.
        assertFalse(CameraPermissionPolicy.isBlocked(rationaleBefore = false, rationaleAfter = true, blockedBefore = false))
        // First ever request, dialog dismissed (Back / tap outside): rationale still false.
        assertFalse(CameraPermissionPolicy.isBlocked(rationaleBefore = false, rationaleAfter = false, blockedBefore = false))
        // Second request, denied again ("don't ask again"): rationale was true, now false.
        assertTrue(CameraPermissionPolicy.isBlocked(rationaleBefore = true, rationaleAfter = false, blockedBefore = false))
        // Second request, dismissed: rationale stays true.
        assertFalse(CameraPermissionPolicy.isBlocked(rationaleBefore = true, rationaleAfter = true, blockedBefore = false))
        // Later requests refused instantly after a block.
        assertTrue(CameraPermissionPolicy.isBlocked(rationaleBefore = false, rationaleAfter = false, blockedBefore = true))
    }
}
