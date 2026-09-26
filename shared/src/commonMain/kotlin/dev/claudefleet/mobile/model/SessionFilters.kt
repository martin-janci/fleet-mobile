package dev.claudefleet.mobile.model

/**
 * How wide a time window the activity filter asks for.
 *
 * The desktop's recency pills (`RECENCY_VALUES` in `src/lib/session_status.ts`)
 * are `8h / 1d / 3d / 7d / 30d`; this adds `1h`, because the phone's question
 * is nearly always "what moved since I last looked" rather than "what ran this
 * month". [label] is written out rather than kept terse — the pills sit in a
 * sheet under a heading here, not in a dense desktop sidebar, and "8h" alone
 * does not say whether it means *within* or *beyond*. [TimeDirection] does.
 */
enum class TimeWindow(val label: String, val seconds: Long?) {
    ANY("Any time", null),
    H1("1 hour", 60 * 60),
    H8("8 hours", 8 * 60 * 60),
    D1("24 hours", 24 * 60 * 60),
    D3("3 days", 3 * 24 * 60 * 60),
    D7("7 days", 7 * 24 * 60 * 60),
    D30("30 days", 30L * 24 * 60 * 60),
}

/**
 * Which side of the window to keep.
 *
 * [WITHIN] is the desktop's recency filter — what has run lately. [BEYOND] is
 * its inverse and has no desktop equivalent: on a fleet screen "what has been
 * sitting untouched for over an hour" is the triage question, and it cannot be
 * asked by narrowing a recency window.
 */
enum class TimeDirection(val label: String) {
    WITHIN("Active within"),
    BEYOND("Idle beyond"),
}

/**
 * One value of a session's state, as the filter sheet offers it.
 *
 * [STUCK] is not a `claude_status` — it is `stuck_kind` being set at all — and
 * it is offered beside the statuses because that is how a person thinks of it.
 * A row can match both it and its status; selections are OR-ed, so that is not
 * a contradiction.
 */
enum class StatusFilter(val label: String, val wire: String?) {
    WORKING("Working", "working"),
    BLOCKED("Blocked", "blocked"),
    STUCK("Stuck", null),
    FAILED("Failed", "failed"),
    COMPLETED("Completed", "completed"),
    IDLE("Idle", "idle"),
    STOPPED("Stopped", "stopped"),
}

/**
 * A ticket's status bucket, as the filter sheet offers it — the desktop's
 * work-filter status chips (`STATUS_FILTERS` in `src/lib/work_filters.ts`).
 *
 * Asked of the session's primary link ([WorkSummary.statusCategory], refreshed
 * from the ticket cache), so a session with no work, or with work the tracker
 * has not given a status, matches none of them — the desktop's rule too: a
 * status filter is a question about tickets, and a row without one is not an
 * answer to it. Selections are OR-ed, like [StatusFilter]'s; the desktop's
 * single choice is the one-chip case of that.
 */
enum class WorkStatusFilter(val label: String, val category: StatusCategory) {
    TODO("To do", StatusCategory.Todo),
    IN_PROGRESS("In progress", StatusCategory.InProgress),
    DONE("Done", StatusCategory.Done),
}

/**
 * Everything that narrows the fleet list, in one value.
 *
 * Deliberately *only* the narrowing. Grouping by work is not in here and never
 * counts towards [activeCount]: it reshapes the list without removing a row
 * from it, and drawing the two as the same kind of chip is what made "By work"
 * read as a filter that does nothing. A person who sees "3 filters" and clears
 * them expects every session back, which is true of these fields and false of
 * a grouping mode.
 *
 * [hostFilter] is held here so that it can be counted and applied with the
 * rest, but the screen does not own it: `Screen.Sessions.hostAlias` is its one
 * source of truth (see `Navigator.clearHostFilter`), so clearing it goes
 * through the navigator and [cleared] leaves it alone.
 */
data class SessionFilters(
    /** Free text, matched against the row and its project ([matches]). Blank matches everything. */
    val query: String = "",
    val needsAttentionOnly: Boolean = false,
    val window: TimeWindow = TimeWindow.ANY,
    val direction: TimeDirection = TimeDirection.WITHIN,
    /** Empty means every status; otherwise a row must match one of them. */
    val statuses: Set<StatusFilter> = emptySet(),
    /** Background agents (`bg:` sessions) are listed. The desktop's `bg on/off`. */
    val showBackground: Boolean = true,
    /** The host the list is narrowed to, or null for the whole fleet. Owned by the navigator. */
    val hostFilter: String? = null,
    val myWorkOnly: Boolean = false,
    val orgFilter: Long? = null,
    /** The project the list is narrowed to, or null for every project. */
    val projectFilter: Long? = null,
    /** Empty means any ticket status; otherwise a row's work must be in one of them. */
    val workStatuses: Set<WorkStatusFilter> = emptySet(),
    /**
     * Sessions archived from the desktop's Tidy-up (work graph M7) are listed.
     * The desktop's `hide archived` chip, the other way up to match
     * [showBackground]: on means the rows are there.
     */
    val showArchived: Boolean = true,
) {
    /**
     * How many filters are on — what the *Filters* chip counts.
     *
     * The time filter counts as one whatever its direction, because a
     * direction with no window narrows nothing.
     */
    val activeCount: Int
        get() = listOf(
            query.isNotBlank(),
            needsAttentionOnly,
            window != TimeWindow.ANY,
            statuses.isNotEmpty(),
            !showBackground,
            hostFilter != null,
            myWorkOnly,
            orgFilter != null,
            projectFilter != null,
            workStatuses.isNotEmpty(),
            !showArchived,
        ).count { it }

    /**
     * What the **Filters** chip counts: [activeCount] less the two that have a
     * control of their own on screen.
     *
     * A badge on a button means "there is something in here" — so counting
     * [needsAttentionOnly], whose chip sits right beside it, made the button
     * read *Filters · 1* over a sheet in which nothing was set, and the one
     * filter it was counting was already visible and already switched on. The
     * same goes for [query], which the search field shows whenever it is set.
     *
     * The summary line still names all of them ([summary]): that line is about
     * what is hiding rows, not about where the control lives.
     */
    val sheetCount: Int
        get() = activeCount - listOf(needsAttentionOnly, query.isNotBlank()).count { it }

    val any: Boolean get() = activeCount > 0

    /**
     * Every filter off — except [hostFilter], which the navigator owns and
     * clears itself. A caller wanting all of them gone clears both.
     */
    fun cleared(): SessionFilters = SessionFilters(hostFilter = hostFilter)

    /**
     * The filters that are on, in words, for the summary line under the chips
     * — the one place that says *what* is hiding rows. Ordered loosely by how
     * surprising each is to have left on.
     */
    fun summary(
        projectName: (Long) -> String = ::unnamedProject,
        // Last, so the trailing-lambda form existing callers use keeps naming the org.
        orgName: (Long) -> String = { "org #$it" },
    ): List<String> = buildList {
        hostFilter?.let { add("Host $it") }
        projectFilter?.let { add(projectName(it)) }
        if (needsAttentionOnly) add("Needs you")
        if (window != TimeWindow.ANY) add("${direction.label} ${window.label}")
        if (statuses.isNotEmpty()) add(statuses.sortedBy { it.ordinal }.joinToString("/") { it.label })
        if (myWorkOnly) add("My work")
        orgFilter?.let { add(orgName(it)) }
        if (workStatuses.isNotEmpty()) {
            add("Ticket " + workStatuses.sortedBy { it.ordinal }.joinToString("/") { it.label.lowercase() })
        }
        if (!showArchived) add("No archived")
        if (!showBackground) add("No background")
        if (query.isNotBlank()) add("\"${query.trim()}\"")
    }
}

/**
 * Does this row survive [filters]?
 *
 * [nowSeconds] is the reference the time window is measured from, and is
 * deliberately *not* the screen's ticking clock: see `SessionsViewModel`'s
 * `filterAt`. [projectLabel] is the row's project as the list draws it, so
 * that typing a repo name finds its sessions — the row itself carries only a
 * `project_id`.
 */
fun SessionRow.matches(filters: SessionFilters, nowSeconds: Long, projectLabel: String?): Boolean {
    if (filters.needsAttentionOnly && !needsAttention) return false
    if (filters.hostFilter != null && hostAlias != filters.hostFilter) return false
    if (!filters.showBackground && isBackground) return false
    if (filters.orgFilter != null && orgOf != filters.orgFilter) return false
    if (filters.projectFilter != null && projectId != filters.projectFilter) return false
    if (!filters.showArchived && work?.archivedAt != null) return false
    if (filters.workStatuses.isNotEmpty() && filters.workStatuses.none { it.category == work?.statusCategory }) {
        return false
    }
    if (filters.statuses.isNotEmpty() && !matchesStatuses(filters.statuses)) return false
    if (!matchesTime(filters, nowSeconds)) return false
    if (!matchesQuery(filters.query, projectLabel)) return false
    return true
}

/** OR across the chosen statuses; [StatusFilter.STUCK] asks a different field. */
private fun SessionRow.matchesStatuses(statuses: Set<StatusFilter>): Boolean = statuses.any { s ->
    when (s) {
        StatusFilter.STUCK -> stuckKind != null
        else -> claudeStatus == s.wire
    }
}

/**
 * The activity window.
 *
 * A row the hub has never stamped (`last_activity_at` absent) is **kept** by
 * every window, in both directions. The desktop drops those — `matchesRecency`
 * returns false on a null timestamp — and porting that here would mean a
 * session created seconds ago, before the hub has stamped it, vanishing the
 * instant someone picks "24 hours": the exact opposite of what the filter was
 * asked for. Unknown is not young and it is not old, so it is not the thing
 * either direction is excluding.
 *
 * A timestamp in the future — a host whose clock runs ahead of the correction
 * in `SessionsViewModel.clock` — reads as age 0 rather than as a negative age,
 * so it counts as *just active* and never as idle for longer than the fleet
 * has existed.
 */
private fun SessionRow.matchesTime(filters: SessionFilters, nowSeconds: Long): Boolean {
    val window = filters.window.seconds ?: return true
    val at = lastActivityAt ?: return true
    val age = (nowSeconds - at).coerceAtLeast(0)
    return when (filters.direction) {
        TimeDirection.WITHIN -> age <= window
        TimeDirection.BEYOND -> age > window
    }
}

/**
 * Free-text match, case-insensitive, across everything the row shows or is
 * filed under: its name, the branch, its tags, the host, the project, and its
 * work's key and title. Blank matches everything.
 */
private fun SessionRow.matchesQuery(query: String, projectLabel: String?): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val fields = sequence {
        yield(displayName)
        yield(tmuxName)
        friendlyName?.let { yield(it) }
        branch?.let { yield(it) }
        yield(hostAlias)
        projectLabel?.let { yield(it) }
        yieldAll(tags)
        work?.let { yield(it.label); yield(it.title) }
        currentActivity?.let { yield(it) }
    }
    return fields.any { it.contains(q, ignoreCase = true) }
}
