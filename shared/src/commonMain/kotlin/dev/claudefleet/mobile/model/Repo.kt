package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One changed file in a session's worktree (`repo_changes`): git status's letter and the path. */
@Serializable
data class ChangedFile(
    val path: String,
    /** git's status: M, A, D, R, ?, … */
    val status: String = "",
    val staged: Boolean = false,
    @SerialName("orig_path") val origPath: String? = null,
)

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
)

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
)

/** "3 not pushed · 5 ahead of main" (the desktop's Files › Changes); null when there is nothing to say. */
fun branchLine(b: BranchDiff): String? {
    val unpushed = b.unpushed.size.takeIf { it > 0 }?.let { "${it}${if (b.truncated) "+" else ""} not pushed" }
    val base = b.base?.substringAfter("origin/")
    val ahead = if (base != null && b.aheadOfBase > 0) "${b.aheadOfBase} ahead of $base" else null
    return listOfNotNull(unpushed, ahead).joinToString(" · ").ifEmpty { null }
}
