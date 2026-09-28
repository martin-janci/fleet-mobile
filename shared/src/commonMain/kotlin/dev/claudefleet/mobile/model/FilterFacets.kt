package dev.claudefleet.mobile.model

/*
 * The active-filter summary: one removable chip per filter that narrows the
 * list, for the Sessions list and My work alike. A port of claude-fleet's
 * `src/lib/filter_facets.ts` (`sessionFacets` / `workFacets` /
 * `withoutWorkFacet` / `facetSentence`), so the phone and the desktop phrase
 * an active filter in the same words and the empty states can name what
 * hides the rows.
 *
 * Pure — strings and ids only. A facet's [Facet.id] is what its owner clears
 * ([SessionFilters.without] / [WorkTreeFilters.without]).
 */

/** One active filter: what clears it, and the chip's text ("Host: gpu-box"). */
data class Facet<out I>(val id: I, val label: String)

/** "Host: gpu-box, Last 1d" — for an empty state. */
fun facetSentence(facets: List<Facet<*>>): String = facets.joinToString(", ") { it.label }

// ── Sessions list ──

/**
 * The Sessions list's filters, in the order their chips read. [NEEDS_YOU]
 * and [SEARCH] have a control of their own on screen, so the strip leaves
 * them out ([onScreen]); the empty state still names them.
 */
enum class SessionFacetId {
    NEEDS_YOU,
    ORG,
    HOST,
    PROJECT,
    TIME,
    SEARCH,
    STATE,
    WORK_STATUS,
    MINE,
    BACKGROUND,
    ;

    /** Shown by its own control (the Needs you chip, the search field): no chip in the strip. */
    val onScreen: Boolean get() = this == NEEDS_YOU || this == SEARCH
}

/**
 * Every filter in [f] that narrows the list, in reading order. Archived
 * sessions being hidden is the default, not a filter anyone set, so it has
 * no facet (the list's *N archived hidden · Show* row says it instead);
 * showing them widens the list, so that has none either.
 */
fun sessionFacets(
    f: SessionFilters,
    orgName: (Long) -> String? = { null },
    projectName: (Long) -> String? = { null },
): List<Facet<SessionFacetId>> = buildList {
    if (f.needsAttentionOnly) add(Facet(SessionFacetId.NEEDS_YOU, "Needs you"))
    f.orgFilter?.let { add(Facet(SessionFacetId.ORG, "Org: ${orgName(it) ?: "#$it"}")) }
    f.hostFilter?.let { add(Facet(SessionFacetId.HOST, "Host: $it")) }
    f.projectFilter?.let { add(Facet(SessionFacetId.PROJECT, "Project: ${projectName(it) ?: "#$it"}")) }
    if (f.window != TimeWindow.ANY) add(Facet(SessionFacetId.TIME, "${f.direction.label} ${f.window.label}"))
    val q = f.query.trim()
    if (q.isNotEmpty()) add(Facet(SessionFacetId.SEARCH, "Search: “$q”"))
    if (f.statuses.isNotEmpty()) {
        add(Facet(SessionFacetId.STATE, "State: " + f.statuses.sortedBy { it.ordinal }.joinToString("/") { it.label }))
    }
    if (f.workStatuses.isNotEmpty() || f.workStatusNames.isNotEmpty()) {
        val buckets = f.workStatuses.sortedBy { it.ordinal }.map { it.label }
        val names = f.workStatusNames.sortedBy { it.lowercase() }
        // A tracker column alone reads as a column, as on the desktop; with
        // a bucket beside it the two are one OR-ed status question.
        val prefix = if (buckets.isEmpty()) "Column" else "Status"
        add(Facet(SessionFacetId.WORK_STATUS, "$prefix: " + (buckets + names).joinToString("/")))
    }
    if (f.myWorkOnly) add(Facet(SessionFacetId.MINE, "Assigned to me"))
    if (!f.showBackground) add(Facet(SessionFacetId.BACKGROUND, "Background agents hidden"))
}

/**
 * [this] without one facet, that field back at its default. [SessionFacetId.HOST]
 * is cleared here too, but the navigator owns the host filter, so its owner
 * must clear `Screen.Sessions.hostAlias` as well (`Navigator.clearHostFilter`).
 */
fun SessionFilters.without(id: SessionFacetId): SessionFilters = when (id) {
    SessionFacetId.NEEDS_YOU -> copy(needsAttentionOnly = false)
    SessionFacetId.ORG -> copy(orgFilter = null)
    SessionFacetId.HOST -> copy(hostFilter = null)
    SessionFacetId.PROJECT -> copy(projectFilter = null)
    SessionFacetId.TIME -> copy(window = TimeWindow.ANY, direction = TimeDirection.WITHIN)
    SessionFacetId.SEARCH -> copy(query = "")
    SessionFacetId.STATE -> copy(statuses = emptySet())
    SessionFacetId.WORK_STATUS -> copy(workStatuses = emptySet(), workStatusNames = emptySet())
    SessionFacetId.MINE -> copy(myWorkOnly = false)
    SessionFacetId.BACKGROUND -> copy(showBackground = true)
}

// ── My work ──

/**
 * The Work view's filters. [MINE], [REVIEW] and [QUERY] show their state in
 * their own controls (two toggles and the search field), so the strip and the
 * *Filters* badge leave them out ([onScreen]); the empty state names them.
 */
enum class WorkFacetId {
    ORG,
    TRACKER,
    STATUS,
    MINE,
    HAS,
    REVIEW,
    QUERY,
    ;

    val onScreen: Boolean get() = this == MINE || this == REVIEW || this == QUERY
}

/** Every filter in [f] that narrows the tree — the desktop's `workFacets`, label for label. */
fun workFacets(
    f: WorkTreeFilters,
    orgName: (Long) -> String? = { null },
    trackerName: (Long) -> String? = { null },
): List<Facet<WorkFacetId>> {
    val n = f.normalized()
    return buildList {
        n.org?.let { org ->
            val name = if (org == IdOrWord.NONE) "Unassigned" else org.id?.let(orgName) ?: "#${org.raw}"
            add(Facet(WorkFacetId.ORG, "Org: $name"))
        }
        n.tracker?.let { t ->
            val name = when (t) {
                IdOrWord.LOCAL -> "Local work"
                IdOrWord.REF -> "Bare keys"
                else -> t.id?.let(trackerName) ?: "#${t.raw}"
            }
            add(Facet(WorkFacetId.TRACKER, "Tracker: $name"))
        }
        n.status?.let { add(Facet(WorkFacetId.STATUS, "Status: ${WorkTreeFilters.statusLabel(it)}")) }
        if (n.mine == true) add(Facet(WorkFacetId.MINE, "Assigned to me"))
        n.has?.let { add(Facet(WorkFacetId.HAS, "Sessions: ${WorkTreeFilters.hasLabel(it)}")) }
        if (n.review == true) add(Facet(WorkFacetId.REVIEW, "To review"))
        n.query?.let { add(Facet(WorkFacetId.QUERY, "Search: “$it”")) }
    }
}

/** The filters without one facet — the desktop's `withoutWorkFacet`. */
fun WorkTreeFilters.without(id: WorkFacetId): WorkTreeFilters {
    val n = normalized()
    return when (id) {
        WorkFacetId.ORG -> n.copy(org = null)
        WorkFacetId.TRACKER -> n.copy(tracker = null)
        WorkFacetId.STATUS -> n.copy(status = null)
        WorkFacetId.MINE -> n.copy(mine = null)
        WorkFacetId.HAS -> n.copy(has = null)
        WorkFacetId.REVIEW -> n.copy(review = null)
        WorkFacetId.QUERY -> n.copy(query = null)
    }
}
