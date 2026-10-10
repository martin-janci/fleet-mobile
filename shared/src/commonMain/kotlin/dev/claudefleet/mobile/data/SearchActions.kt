package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.SearchPage

/** Search everywhere's Text rows: the hub's full-text `search`, readonly. */
fun interface SearchActions {
    suspend fun search(query: String): SearchPage
}

class HubSearchActions(private val session: AppSession) : SearchActions {
    override suspend fun search(query: String): SearchPage = session.withClient { it.search(query) }
}
