package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.BranchDiff
import dev.claudefleet.mobile.model.ChangedFile
import dev.claudefleet.mobile.model.Commit
import dev.claudefleet.mobile.model.CommitDetail
import dev.claudefleet.mobile.model.FileContent
import dev.claudefleet.mobile.model.FileDiff
import dev.claudefleet.mobile.model.RepoTree

/**
 * What a session's worktree screen reads — every call a readonly tool, so a
 * `readonly` pairing sees all of it. Nothing here writes to the worktree:
 * stage, commit, checkout and push stay on the desktop, where the hub
 * refuses them to a client anyway. Sending a file to Downloads is
 * [DownloadActions.sendFile].
 */
interface RepoActions {
    suspend fun changes(sessionId: Long): List<ChangedFile>
    suspend fun diff(sessionId: Long, path: String): FileDiff
    suspend fun log(sessionId: Long, skip: Int = 0): List<Commit>
    suspend fun commit(sessionId: Long, hash: String): CommitDetail
    suspend fun commitDiff(sessionId: Long, hash: String, path: String): FileDiff
    suspend fun tree(sessionId: Long): RepoTree
    suspend fun file(sessionId: Long, path: String): FileContent

    /** Null where the hub cannot say (and in fakes that do not care). */
    suspend fun branch(sessionId: Long): BranchDiff? = null
}

class HubRepoActions(private val session: AppSession) : RepoActions {
    override suspend fun changes(sessionId: Long) = session.withClient { it.repoChanges(sessionId) }
    override suspend fun diff(sessionId: Long, path: String) = session.withClient { it.repoDiff(sessionId, path) }
    override suspend fun log(sessionId: Long, skip: Int) = session.withClient { it.repoLog(sessionId, skip = skip) }
    override suspend fun commit(sessionId: Long, hash: String) = session.withClient { it.repoCommit(sessionId, hash) }
    override suspend fun commitDiff(sessionId: Long, hash: String, path: String) =
        session.withClient { it.repoCommitDiff(sessionId, hash, path) }
    override suspend fun tree(sessionId: Long) = session.withClient { it.repoTree(sessionId) }
    override suspend fun file(sessionId: Long, path: String) = session.withClient { it.repoFile(sessionId, path) }
    override suspend fun branch(sessionId: Long): BranchDiff? = session.withClient { it.repoBranchDiff(sessionId) }
}
