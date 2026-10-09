package dev.claudefleet.mobile.ui

/*
 * The session ⋮ menu on the New bar (redesign 14.14, MobileSessionExtras):
 * everything the Classic ⋮ held, written out with what each item does.
 * Recovery sits in the middle; Kill is last and asks first. Send later hands
 * the hub a prompt for the session's next idle moment (claude-fleet's deferred
 * prompts, 5.10), so it is sent whether or not the phone is still running.
 */

/** What the session screen hands the New bar's menu beyond the Classic one's callbacks. */
class OrbitMenu(
    /** ⋮ Archive; null where the hub or pairing cannot. */
    val onArchive: (() -> Unit)? = null,
    /** The ticket sheet; null when the session has no ticket and none may be set. */
    val onTicket: (() -> Unit)? = null,
    val ticketKey: String? = null,
    /** The tasks sheet, where the hub has tasks; Ticket and tasks opens it when there is no ticket sheet. */
    val onTasks: (() -> Unit)? = null,
    /** Send later's sheet and the paused row's answers; the default does nothing. */
    val later: SessionLaterHost = SessionLaterHost(),
)

enum class OrbitItem {
    Rename, Ticket, Tags,
    Model, Effort, Review, SendLater, Background,
    Move, Repair, Recreate, Restart, DismissGhost,
    CopyAttach, Details, Worktree,
    Archive, Retire, Kill,
}

/** One line of the menu: its words, what it does, its group (a divider between groups). */
data class OrbitMenuItem(
    val id: OrbitItem,
    val label: String,
    val detail: String?,
    val group: Int,
    /** Changes something, so it waits while the session is busy or the hub is away. */
    val writes: Boolean = true,
    val danger: Boolean = false,
)

/** What decides which items the menu shows; each flag is the Classic menu's own condition for it. */
data class OrbitMenuFacts(
    val manage: Boolean = false,
    val hostAlias: String? = null,
    val tmuxName: String? = null,
    val ticketKey: String? = null,
    val ticket: Boolean = false,
    val tasks: Boolean = false,
    val move: Boolean = false,
    val repair: Boolean = false,
    val recreate: Boolean = false,
    val ghost: Boolean = false,
    val dismissGhost: Boolean = false,
    val restart: Boolean = false,
    val steer: Boolean = false,
    val review: Boolean = false,
    val archive: Boolean = false,
    val kill: Boolean = false,
    val worktree: Boolean = false,
    /** The hub keeps prompts for later and this person may drive the session. */
    val sendLater: Boolean = false,
    /** The hub starts background agents and this person manages the session. */
    val background: Boolean = false,
)

/** The command that attaches a terminal to the session on its host, as the desktop's Details copies it. */
fun tmuxAttachCommand(tmuxName: String): String = "tmux attach -t $tmuxName"

/** The menu's lines, in order. */
fun orbitMenuItems(f: OrbitMenuFacts): List<OrbitMenuItem> = buildList {
    if (f.manage) add(OrbitMenuItem(OrbitItem.Rename, "Rename", null, 0))
    if (f.ticket || f.tasks) add(OrbitMenuItem(OrbitItem.Ticket, "Ticket and tasks", f.ticketKey ?: "link a ticket", 0, writes = false))
    if (f.manage) add(OrbitMenuItem(OrbitItem.Tags, "Tags…", "labels to filter by", 0))
    if (f.steer) {
        add(OrbitMenuItem(OrbitItem.Model, "Model…", "switches with /model", 1))
        add(OrbitMenuItem(OrbitItem.Effort, "Effort…", "sets /effort", 1))
    }
    if (f.review) add(OrbitMenuItem(OrbitItem.Review, "Review…", "a new session reviews this worktree", 1))
    if (f.sendLater) add(OrbitMenuItem(OrbitItem.SendLater, "Send later…", "goes in when the session is next idle", 1))
    if (f.background) add(OrbitMenuItem(OrbitItem.Background, "Background agent…", "runs without a pane; the result lands in Inbox", 1))
    if (f.move) add(OrbitMenuItem(OrbitItem.Move, "Move to host…", f.hostAlias?.let { "now on $it" }, 2))
    if (f.repair) add(OrbitMenuItem(OrbitItem.Repair, "Repair workspace", "fix the worktree, re-attach the pane", 2))
    if (f.recreate) add(OrbitMenuItem(OrbitItem.Recreate, "Recreate", if (f.ghost) "bring it back in its worktree" else "new pane, same worktree and conversation", 2))
    if (f.restart) add(OrbitMenuItem(OrbitItem.Restart, "Restart", "kill and recreate the tmux session", 2))
    if (f.dismissGhost) add(OrbitMenuItem(OrbitItem.DismissGhost, "Dismiss lost session", "forget this row; the conversation stays", 2))
    f.tmuxName?.takeIf { it.isNotBlank() }?.let {
        add(OrbitMenuItem(OrbitItem.CopyAttach, "Copy tmux attach command", tmuxAttachCommand(it), 3, writes = false))
    }
    add(OrbitMenuItem(OrbitItem.Details, "Details", "host, branch, model, timeline", 3, writes = false))
    if (f.worktree) add(OrbitMenuItem(OrbitItem.Worktree, "Files", "changes, history, the worktree", 3, writes = false))
    if (f.archive) add(OrbitMenuItem(OrbitItem.Archive, "Archive", "off the work board", 4))
    if (f.manage) add(OrbitMenuItem(OrbitItem.Retire, "Safe remove", "commit and push, then remove", 4))
    if (f.kill) add(OrbitMenuItem(OrbitItem.Kill, "Kill session…", "asks first", 4, danger = true))
}

const val ORBIT_MENU_TAG = "session.menu."
