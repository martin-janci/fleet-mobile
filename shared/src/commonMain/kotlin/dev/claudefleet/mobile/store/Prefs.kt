package dev.claudefleet.mobile.store

/**
 * Small string-list storage for on-device UI preferences — currently the
 * quick-reply chips and the draft history, both in `ui/QuickReplies.kt`.
 *
 * Deliberately an interface rather than an `expect class`, for the same
 * reason [Secrets] is: Android's implementation needs a `Context` (it wraps a
 * `SharedPreferences` instance), iOS's needs nothing
 * (`NSUserDefaults.standardUserDefaults`), and an `expect class` forces one
 * shared constructor signature on both. See [Secrets]'s own class comment for
 * the fuller argument; nothing stored here is a credential, but the
 * per-platform constructor shape is the same problem either way.
 */
interface Prefs {
    /** The stored list for [key], or empty when nothing has been written yet. */
    fun getStringList(key: String): List<String>

    /** Replace whatever is stored for [key]. */
    fun putStringList(key: String, value: List<String>)
}
