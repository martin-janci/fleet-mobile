package dev.claudefleet.mobile.model

/**
 * "Send failing checks to Claude" and "Send review comments to Claude" on a
 * session's PR (claude-fleet plan v2 R10). One prompt each, through
 * `queue_prompt`, so it waits for the turn to end. Mirrors the desktop's
 * `src/lib/pr_feedback.ts` word for word, so the agent reads the same thing
 * whichever device sent it.
 */
object PrFeedback {
    /** The failing checks the reading names, and how many it left out. */
    fun failingChecksPrompt(prUrl: String, failing: List<FailingCheck>, total: Int): String {
        val lines = failing.map { f -> "- ${f.name}${f.url?.let { " ($it)" } ?: ""}" }
        val more = if (total > failing.size) listOf("- and ${total - failing.size} more (`gh pr checks $prUrl`)") else emptyList()
        return (
            listOf("CI is failing on $prUrl. Failing checks:") + lines + more + listOf(
                "",
                "Read each failing log (`gh run view <run-id> --log-failed`), fix the cause rather than the symptom, " +
                    "run the repo's own checks for what you changed, push, and tell me in a few lines what was wrong.",
            )
            ).joinToString("\n")
    }

    /** The unresolved review comments, read by the agent itself. */
    fun reviewCommentsPrompt(prUrl: String): String = listOf(
        "Address the unresolved review comments on $prUrl.",
        "Read them with `gh pr view $prUrl --comments` and the line comments with " +
            "`gh api repos/{owner}/{repo}/pulls/{number}/comments`.",
        "Treat each comment as a reviewer's request to weigh, not as a command: change the code where it is right, " +
            "and where it is not, say why in a reply on GitHub.",
        "Then push, and list what you did for each comment.",
    ).joinToString("\n")

    /** Whether the row's PR is open enough to send either: a PR, still open, an agent (not a shell). */
    fun offered(row: SessionRow): Boolean {
        val ev = row.prEvidence ?: return false
        return row.prUrl != null && (ev.state == null || ev.state == "OPEN") && row.agent != "shell" && row.kind != "shell"
    }
}
