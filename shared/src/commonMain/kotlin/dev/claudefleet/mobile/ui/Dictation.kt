package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/**
 * The composer's mic (MobileSession: "voice input sits in the composer"):
 * the platform's own speech recogniser, whose words go to [onText] to be
 * added to the draft ([withDictation]) — never sent; the person still reads
 * them and taps Send. Null where this platform or device has no recogniser,
 * and the composer then draws no mic at all rather than one that does
 * nothing.
 *
 * Android hands the listening to the system's recogniser activity
 * (`RecognizerIntent`), which asks for the microphone itself, so this app
 * declares no audio permission. iOS answers null for now: its recogniser
 * (`SFSpeechRecognizer`) needs two permission prompts and an audio session
 * of the app's own, and the keyboard's dictation key already covers it there.
 */
@Composable
internal expect fun rememberDictation(onText: (String) -> Unit): (() -> Unit)?
