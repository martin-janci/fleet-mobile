package dev.claudefleet.mobile.store

/** An in-memory [Prefs], a `MutableMap` standing in for the platform store. */
class FakePrefs : Prefs {
    private val map = mutableMapOf<String, List<String>>()

    override fun getStringList(key: String): List<String> = map[key] ?: emptyList()

    override fun putStringList(key: String, value: List<String>) {
        map[key] = value
    }
}
