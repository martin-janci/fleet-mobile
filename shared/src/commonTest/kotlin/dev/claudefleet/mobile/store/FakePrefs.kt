package dev.claudefleet.mobile.store

/** An in-memory [Prefs], a `MutableMap` standing in for the platform store. */
class FakePrefs : Prefs {
    private val map = mutableMapOf<String, List<String>>()

    /** The keys holding something, for a test that a flow left nothing behind. */
    val written: Set<String> get() = map.filterValues { it.isNotEmpty() }.keys

    override fun getStringList(key: String): List<String> = map[key] ?: emptyList()

    override fun putStringList(key: String, value: List<String>) {
        map[key] = value
    }
}
