package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One changed file in a session's worktree (`repo_changes`) or a commit
 * (`repo_commit`): git status's letter and the path, and the lines it adds
 * and removes (claude-fleet M15 G1.10). The counts are absent for a binary or
 * untracked file, and from an older hub.
 */
@Serializable
data class ChangedFile(
    val path: String,
    /** git's status: M, A, D, R, ?, … */
    val status: String = "",
    val staged: Boolean = false,
    @SerialName("orig_path") val origPath: String? = null,
    val added: Int? = null,
    val removed: Int? = null,
)

/** "+12 −3" for a file's line counts; null when the hub sent none. */
fun lineCounts(f: ChangedFile): String? {
    if (f.added == null && f.removed == null) return null
    return "+${f.added ?: 0} −${f.removed ?: 0}"
}

/** A worktree's files (`repo_tree`), tracked and untracked, gitignore respected. */
@Serializable
data class RepoTree(
    val entries: List<String> = emptyList(),
    val truncated: Boolean = false,
)

/** One file's contents (`repo_file`), capped by the hub. */
@Serializable
data class FileContent(
    val path: String,
    val content: String = "",
    val truncated: Boolean = false,
    val binary: Boolean = false,
    @SerialName("is_dir") val isDir: Boolean = false,
    val size: Long? = null,
)

/** A unified diff of one file (`repo_diff` against HEAD, `repo_commit_diff` in a commit). */
@Serializable
data class FileDiff(
    val path: String,
    val diff: String = "",
    val binary: Boolean = false,
    val truncated: Boolean = false,
)

/** A ref decoration on a commit: a branch, a remote branch, a tag, HEAD. */
@Serializable
data class GitRef(val name: String, val kind: String = "")

/** One commit of the log (`repo_log`, newest first). The hub sends these in camelCase. */
@Serializable
data class Commit(
    val hash: String,
    val shortHash: String = "",
    val parents: List<String> = emptyList(),
    val refs: List<GitRef> = emptyList(),
    val author: String = "",
    val date: String = "",
    val subject: String = "",
)

/** One commit with its files (`repo_commit`). */
@Serializable
data class CommitDetail(
    val hash: String,
    val subject: String = "",
    val body: String = "",
    val author: String = "",
    val date: String = "",
    val files: List<ChangedFile> = emptyList(),
    /** A remote-tracking branch contains it (claude-fleet M15 G1.10); null from an older hub. */
    val pushed: Boolean? = null,
)

/** "Pushed" / "Not pushed"; null when the hub did not say. */
fun pushedLabel(c: CommitDetail): String? = c.pushed?.let { if (it) "Pushed" else "Not pushed" }

/**
 * What a session's branch carries (`repo_branch_diff`, readonly): the commits
 * no remote has, and how far HEAD is past the base branch. The phone shows
 * the two counts; the commits and files themselves pass by.
 */
@Serializable
data class BranchDiff(
    val branch: String? = null,
    val upstream: String? = null,
    val unpushed: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    /** More unpushed commits than the hub lists. */
    val truncated: Boolean = false,
    val base: String? = null,
    @SerialName("aheadOfBase") val aheadOfBase: Int = 0,
    /** Commits on the base since the branch left it (claude-fleet M15 G1.10); null without a base and from an older hub. */
    @SerialName("behindBase") val behindBase: Int? = null,
)

/**
 * "3 not pushed · 5 ahead of main · 2 behind main" (the desktop's Files ›
 * Changes); null when there is nothing to say.
 */
fun branchLine(b: BranchDiff): String? {
    val unpushed = b.unpushed.size.takeIf { it > 0 }?.let { "${it}${if (b.truncated) "+" else ""} not pushed" }
    val base = b.base?.substringAfter("origin/")
    val ahead = if (base != null && b.aheadOfBase > 0) "${b.aheadOfBase} ahead of $base" else null
    val behind = b.behindBase?.takeIf { base != null && it > 0 }?.let { "$it behind $base" }
    return listOfNotNull(unpushed, ahead, behind).joinToString(" · ").ifEmpty { null }
}

/**
 * A ticket named in a commit message: a tracker key (`FLEET-142`) or a GitHub
 * issue number (`#88`), and where it sits in the text ([start] inclusive,
 * [end] exclusive), so the commit screen can draw it as a link.
 */
data class TicketRef(val key: String, val start: Int, val end: Int)

/**
 * Tracker keys — a capital, one to nine more capitals or digits, a dash, a
 * number — and `#N`. Whether one stands alone (not inside `abc-FOO-1` or
 * `a#1`) is checked by hand in [ticketRefs], which keeps the pattern free of
 * look-arounds every platform's regex engine reads alike.
 */
private val TICKET_REF = Regex("""(?:[A-Z][A-Z0-9]{1,9}-[0-9]+|#[0-9]+)""")

/** What may not touch a ticket ref on either side for it to stand alone. */
private fun joins(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-' || c == '#' || c == '/'

/** Every ticket named in [text], in order. Words like `UTF-8` or `SHA-256` are not tickets. */
fun ticketRefs(text: String): List<TicketRef> =
    TICKET_REF.findAll(text)
        .filter { m ->
            val before = m.range.first - 1
            val after = m.range.last + 1
            (before < 0 || !joins(text[before])) && (after >= text.length || !joins(text[after]))
        }
        .filter { m -> m.value.startsWith("#") || m.value.substringBefore('-') !in NOT_TICKETS }
        .map { TicketRef(it.value, it.range.first, it.range.last + 1) }
        .toList()

/** Prefixes that look like a tracker key and are not one. */
private val NOT_TICKETS = setOf("UTF", "SHA", "ISO", "RFC", "HTTP", "TLS", "CVE", "PEP", "MD", "AES", "RSA", "UUID", "IPV")

/**
 * Where a ticket named in a commit opens, or null when the phone does not
 * know: the session's own ticket ([work]) opens at the tracker's link the hub
 * stamped on it, and `#N` opens on the project's GitHub. Another tracker's key
 * has no address the phone could build, so it stays plain text.
 */
fun ticketRefUrl(key: String, work: WorkSummary?, project: ProjectRow?): String? {
    val workKey = work?.key
    if (workKey != null && workKey.equals(key, ignoreCase = true)) return work?.url?.takeIf { it.isNotBlank() }
    if (key.startsWith("#")) return githubRepoUrl(project)?.let { "$it/issues/${key.drop(1)}" }
    return null
}

/** The project on GitHub, `https://github.com/owner/repo`; null without both halves. */
fun githubRepoUrl(project: ProjectRow?): String? {
    if (project == null || project.owner.isBlank() || project.repo.isBlank()) return null
    return "https://github.com/${project.owner}/${project.repo}"
}

/** A commit on GitHub — **Open on GitHub** on the commit screen; null where the project's owner/repo is unknown. */
fun githubCommitUrl(project: ProjectRow?, hash: String): String? =
    githubRepoUrl(project)?.takeIf { hash.isNotBlank() }?.let { "$it/commit/$hash" }
