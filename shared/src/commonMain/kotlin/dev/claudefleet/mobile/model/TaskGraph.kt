package dev.claudefleet.mobile.model

/*
 * The task graph card (mission control hardening, claude-fleet #914 and
 * #921): a mission's steps, or a proposed tree of tasks, laid out by wave,
 * each tinted by its state, with "3 of 5 done". A port of the desktop's
 * `src/lib/task_graph_card.ts`. Pure.
 */

/** done, working, waiting (needs you), failed, idle: how a step is tinted. */
enum class StepTone { DONE, WORKING, WAITING, FAILED, IDLE }

data class GraphStep(
    val id: Long,
    val title: String,
    val tone: StepTone,
    /** The state in words, under the title. */
    val sub: String,
    /** The step the work is at now: the first that needs you, else running. */
    val current: Boolean = false,
)

data class GraphCard(val title: String, val waves: List<List<GraphStep>>, val done: Int, val total: Int) {
    /** "3 of 5 done". */
    val progress: String get() = "$done of $total done"
}

/** The most steps a wave shows before "+N". */
const val STEPS_PER_WAVE = 3

/** A mission node's state as a tone: the desktop's `mission_graph.toneOf`. */
fun nodeTone(state: String): StepTone = when (state) {
    "done" -> StepTone.DONE
    "running", "doing", "verifying" -> StepTone.WORKING
    "failed", "blocked" -> StepTone.FAILED
    // A proposal waits for a person to accept it: Needs you.
    "proposed" -> StepTone.WAITING
    else -> StepTone.IDLE
}

/** A node's state in words: "Working", "Needs you · proposed"; the desktop's `nodeLabel`. */
fun nodeLabel(state: String): String = when (state) {
    "done" -> "Done"
    "running", "doing" -> "Working"
    "verifying" -> "Working · verifying"
    "failed" -> "Failed"
    "blocked" -> "Failed · blocked"
    "proposed" -> "Needs you · proposed"
    "held" -> "Paused"
    "waiting", "ready", "rejected" -> "Idle · $state"
    else -> if (state.isBlank()) "Idle" else "Idle · $state"
}

/** A work item's status category as a tone: the desktop's `handoffs.workState`. */
fun workTone(status: String?): StepTone = when (status) {
    "active", "in_progress", "running" -> StepTone.WORKING
    "completed", "done" -> StepTone.DONE
    "failed", "cancelled" -> StepTone.FAILED
    "blocked" -> StepTone.WAITING
    else -> StepTone.IDLE
}

private fun markCurrent(waves: List<List<GraphStep>>): List<List<GraphStep>> {
    val flat = waves.flatten()
    val at = flat.firstOrNull { it.tone == StepTone.WAITING } ?: flat.firstOrNull { it.tone == StepTone.WORKING } ?: return waves
    return waves.map { w -> w.map { if (it === at) it.copy(current = true) else it } }
}

/** A mission's graph: its nodes by wave, titled by their items. Null when it has no steps yet (a draft, an older hub). */
fun graphFromMission(d: MissionDetail): GraphCard? {
    val nodes = d.graph.nodes.filter { it.state != "rejected" }
    if (nodes.isEmpty()) return null
    val titles = d.items.associate { it.id to it.title.ifBlank { it.key ?: "Task ${it.id}" } }
    val waves = nodes
        .groupBy { maxOf(1, it.wave) }
        .entries.sortedBy { it.key }
        .map { it.value }
        .map { w -> w.map { n -> GraphStep(n.itemId, titles[n.itemId] ?: "Task ${n.itemId}", nodeTone(n.state), nodeLabel(n.state)) } }
    val steps = waves.flatten()
    return GraphCard(
        // Drawn inside the mission's own page, which already names it.
        title = "Task graph",
        waves = markCurrent(waves),
        done = steps.count { it.tone == StepTone.DONE },
        total = steps.size,
    )
}

/** A proposed tree's items by wave, from their `depends_on`; a cycle the store refuses never loops. */
fun treeWaves(items: List<HandoffItem>): List<List<HandoffItem>> {
    val byId = items.associateBy { it.id }
    val memo = HashMap<Long, Int>()
    fun wave(i: HandoffItem, seen: MutableSet<Long>): Int {
        memo[i.id]?.let { return it }
        if (!seen.add(i.id)) return 1
        var w = 1
        for (d in i.dependsOn) byId[d]?.let { w = maxOf(w, wave(it, seen) + 1) }
        memo[i.id] = w
        return w
    }
    return items.groupBy { wave(it, HashSet()) }.entries.sortedBy { it.key }.map { it.value }
}

/** A proposed tree as a graph; null with no items. */
fun graphFromTree(h: ControlHandoff): GraphCard? {
    if (h.items.isEmpty()) return null
    val waves = treeWaves(h.items).map { w ->
        w.map { i ->
            val proposed = i.proposalState == "proposed"
            GraphStep(
                id = i.id,
                title = i.title.ifBlank { "Task ${i.id}" },
                tone = if (proposed) StepTone.IDLE else workTone(i.status),
                sub = if (proposed) "proposed" else i.status.replace('_', ' '),
            )
        }
    }
    return GraphCard(
        title = h.item?.title?.ifBlank { null } ?: "Proposed tasks",
        waves = markCurrent(waves),
        done = h.items.count { workTone(it.status) == StepTone.DONE },
        total = h.items.size,
    )
}
