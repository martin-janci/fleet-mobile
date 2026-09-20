@file:OptIn(ExperimentalForeignApi::class)

package dev.claudefleet.mobile.ui.scan

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureSession
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capture graph, actually executed.
 *
 * The file this tests carried the note "**Unrun.** This compiles for `iosArm64`
 * and `iosSimulatorArm64` and has never executed". That is the interesting part:
 * a cinterop binding that compiles is not a binding that works, and the failure
 * mode for getting one wrong on Kotlin/Native is an uncatchable Objective-C
 * trap, not an exception some test could report.
 *
 * The simulator has no camera, so `defaultDeviceWithMediaType` returns null and
 * [startCapturing] takes its first `return false`. That is a narrow path, and
 * it is the only one reachable without hardware — but it is the difference
 * between "this code has never run" and "this code runs, links AVFoundation,
 * and declines a machine with no camera instead of trapping on it".
 */
class QrScannerCaptureTest {

    private object NoopDelegate : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol

    /** Every iPhone has a camera; this is a constant and it should stay one. */
    @Test
    fun the_scanner_is_supported_on_ios() {
        assertTrue(qrScannerSupported())
    }

    /**
     * No camera is a "no", not a crash.
     *
     * This is also what happens on a real device when another app holds the
     * camera, which is the case the whole boolean return exists for: the
     * screen keeps working and tells the person to type the eight characters.
     */
    @Test
    fun a_machine_with_no_camera_declines() {
        val session = AVCaptureSession()

        assertFalse(
            session.startCapturing(NoopDelegate),
            "with no capture device available, startCapturing must answer false",
        )
        assertFalse(session.isRunning(), "a session that never started must not be running")
    }

    /**
     * And declining is repeatable.
     *
     * `beginConfiguration` without a matching `commitConfiguration` leaves the
     * session wedged, and each early return in [startCapturing] is a separate
     * chance to get that pairing wrong. A second call on the same session is
     * the cheapest way to notice: a session left mid-configuration does not
     * behave the same way twice.
     */
    @Test
    fun declining_twice_leaves_the_session_usable() {
        val session = AVCaptureSession()

        repeat(2) { assertFalse(session.startCapturing(NoopDelegate)) }
        assertTrue(session.inputs.isEmpty(), "a failed start must not leave inputs attached")
    }
}
