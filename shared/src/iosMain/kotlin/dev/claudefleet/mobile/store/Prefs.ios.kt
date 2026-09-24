package dev.claudefleet.mobile.store

import platform.Foundation.NSUserDefaults

/**
 * iOS's [Prefs]: `NSUserDefaults.standardUserDefaults`, directly — no JSON
 * encoding needed, unlike [AndroidPrefs], because `NSUserDefaults` already
 * stores a string array as a native property-list type.
 *
 * Nothing stored through here is a credential — see [AndroidPrefs]'s class
 * comment for why that is what makes the plain (not Keychain) store the right
 * one for this.
 */
class IosPrefs(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : Prefs {
    override fun getStringList(key: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        return (defaults.stringArrayForKey(key) as? List<String>) ?: emptyList()
    }

    override fun putStringList(key: String, value: List<String>) {
        defaults.setObject(value, key)
    }
}
