package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.store.Prefs

/** The key the phone's layout switch is stored under; the desktop's is also `ui.layout`. */
internal const val LAYOUT_PREF = "ui.layout"

/** The stored layout; Classic until someone switches, and for anything this version cannot read. */
fun loadPhoneLayout(prefs: Prefs): PhoneLayout =
    if (prefs.getStringList(LAYOUT_PREF).firstOrNull() == "new") PhoneLayout.New else PhoneLayout.Classic

fun savePhoneLayout(prefs: Prefs, layout: PhoneLayout) =
    prefs.putStringList(LAYOUT_PREF, listOf(layout.name.lowercase()))
