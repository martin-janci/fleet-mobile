package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.store.Prefs

/** The key the phone's layout switch is stored under; the desktop's is also `ui.layout`. */
internal const val LAYOUT_PREF = "ui.layout"

/**
 * The stored layout. New is the default (gap plan G5.4, the 14.13 sign-off):
 * Classic only for a phone that chose it, and New for anything else — nothing
 * stored, or a value this version cannot read.
 */
fun loadPhoneLayout(prefs: Prefs): PhoneLayout =
    if (prefs.getStringList(LAYOUT_PREF).firstOrNull() == "classic") PhoneLayout.Classic else PhoneLayout.New

fun savePhoneLayout(prefs: Prefs, layout: PhoneLayout) =
    prefs.putStringList(LAYOUT_PREF, listOf(layout.name.lowercase()))
