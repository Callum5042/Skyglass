package com.skyglass.ui

/** CameraX's `CameraState.Type`, without the dependency so the rules below can be unit-tested. */
enum class CameraPhase { PENDING_OPEN, OPENING, OPEN, CLOSING, CLOSED }

/** CameraX's `CameraState.StateError.type`, or [NONE] when the state carries no error. */
enum class CameraErrorKind { NONE, RECOVERABLE, CRITICAL }

enum class CameraVerdict {
    /** Nothing to report. */
    OK,

    /** CameraX cannot open the camera yet but keeps retrying (e.g. another app is using it). Stay in camera mode and say so. */
    WAITING,

    /** The camera will not open (disabled by policy, fatal error, do-not-disturb, bad stream config): leave camera mode. */
    FATAL,
}

/**
 * Turns the stream of `CameraState`s from one bind into verdicts.
 *
 *  - `CRITICAL` errors are fatal. `ERROR_CAMERA_IN_USE` and `ERROR_MAX_CAMERAS_IN_USE` are
 *    `RECOVERABLE`: CameraX sits in `PENDING_OPEN`/`OPENING` and retries, so they only produce
 *    [CameraVerdict.WAITING]. That holds until the camera is `OPEN` (CameraX can publish an
 *    error-free `OPENING` or `PENDING_OPEN` between retries, which must not clear the hint).
 *  - `LiveData.observeForever` delivers the current value at once, which right after a bind can be
 *    the *previous* session's `CLOSED` state still carrying its error (e.g. after `CAMERA_DISABLED`,
 *    once the policy has been lifted); `cameraState` is a mediator, so there may be two such
 *    values. Everything delivered during `observeForever` is therefore ignored: call [attached]
 *    right after it returns. Later deliveries are real, even if CameraX's `postValue` coalesced
 *    a clean state and the error that follows it into one. Make a new tracker for each bind.
 */
class CameraStateTracker {

    private var attached = false
    private var waiting = false

    /** Call once `observeForever` has returned: from now on states are the new session's. */
    fun attached() {
        attached = true
    }

    fun onState(phase: CameraPhase, error: CameraErrorKind): CameraVerdict {
        if (!attached) return CameraVerdict.OK
        if (error == CameraErrorKind.CRITICAL) {
            waiting = false
            return CameraVerdict.FATAL
        }
        if (phase == CameraPhase.OPEN) {
            waiting = false
            return CameraVerdict.OK
        }
        if (error == CameraErrorKind.RECOVERABLE && (phase == CameraPhase.PENDING_OPEN || phase == CameraPhase.OPENING)) {
            waiting = true
        }
        return if (waiting) CameraVerdict.WAITING else CameraVerdict.OK
    }
}

/** Decides whether the system will still show the camera permission dialog. */
object CameraPermissionPolicy {

    /**
     * After a denied request: true if only the app's settings page can grant the permission now.
     *
     * `shouldShowRequestPermissionRationale` is false both before the first request and once the
     * user has chosen "don't ask again" (or denied twice, on Android 11+), and on Android 11+ a
     * dialog dismissed with Back or a tap outside returns "denied" without counting as a denial.
     * So false *after* the request means blocked only if the dialog could have been refused:
     *
     * @param rationaleBefore the value just before launching the request: true means the user had
     *   denied once and this request really showed the dialog
     * @param rationaleAfter the value in the result callback
     * @param blockedBefore an earlier request was found blocked and the permission has not been
     *   granted since; later requests are refused instantly, which looks like a dismissed dialog
     */
    fun isBlocked(rationaleBefore: Boolean, rationaleAfter: Boolean, blockedBefore: Boolean): Boolean =
        !rationaleAfter && (rationaleBefore || blockedBefore)
}
