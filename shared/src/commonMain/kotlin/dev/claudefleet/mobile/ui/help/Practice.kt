package dev.claudefleet.mobile.ui.help

import dev.claudefleet.mobile.model.SessionRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The practice fleet (MobileTutorials): sample sessions on a pretend host,
 * built from fixtures in the app. It works before pairing and never reaches
 * the hub: this file imports nothing that can (`PracticeNeverReachesTheHubTest`
 * holds it to that). An answer here changes only this object.
 */
object PracticeFixtures {
    /** "Now", for the fixtures' ages: a fixed instant, so the sample reads the same every time. */
    const val NOW: Long = 1_760_000_000

    val demo = SessionRow(
        id = -1,
        tmuxName = "demo-flaky-test",
        friendlyName = "Demo: Fix a flaky test",
        hostAlias = "practice-host",
        status = "running",
        claudeStatus = "blocked",
        currentActivity = "Allow Bash? git push origin fix-login-flake",
        lastActivityAt = NOW - 120,
    )

    val sessions: List<SessionRow> = listOf(
        demo,
        SessionRow(
            id = -2,
            tmuxName = "demo-docs",
            friendlyName = "Demo: Update the README",
            hostAlias = "practice-host",
            status = "running",
            claudeStatus = "working",
            currentActivity = "Editing README.md",
            lastActivityAt = NOW - 30,
        ),
        SessionRow(
            id = -3,
            tmuxName = "demo-release",
            friendlyName = "Demo: Tag a release",
            hostAlias = "practice-host",
            status = "stopped",
            claudeStatus = "completed",
            currentActivity = "Tagged v1.2.0",
            lastActivityAt = NOW - 3_600,
        ),
    )

    /** The demo session's conversation: who said it, and what. */
    val conversation: List<Pair<String, String>> = listOf(
        "You" to "Fix the flaky login test and push.",
        "Claude Code" to "✓ Run npm test · 41 passed",
        "Claude Code" to "Fixed. Ready to push.",
    )

    const val QUESTION = "Allow Bash? git push origin fix-login-flake"

    val choices: List<String> = listOf(
        "Yes",
        "Yes, and don't ask again for git push",
        "No, tell Claude what to do",
    )

    /** The word every practice notification carries, so it is never mistaken for the real fleet's. */
    const val PRACTICE_MARK = "Practice"

    /**
     * The practice fleet's Needs you notification (MobileTutorials: "its
     * notifications are marked Practice"). The same words a real one has,
     * built from the demo row, with "Practice · " on its title and its lock
     * screen line, no buttons (a practice answer is a tap in the app, never a
     * notification action), and the demo's negative id, which no hub session
     * has. It is drawn inside the practice fleet only: nothing is posted to
     * the system, so it cannot sit in the shade after the person leaves.
     */
    fun notification(): PracticeNotification {
        val name = demo.friendlyName ?: demo.tmuxName
        return PracticeNotification(
            sessionId = demo.id,
            title = "$PRACTICE_MARK · $name needs you",
            body = "$QUESTION\nNeeds you · ${demo.hostAlias}",
            publicBody = "$PRACTICE_MARK · Needs you · ${demo.hostAlias}",
        )
    }

    /** What the pretend session does with each answer, so the person sees an answer land. */
    fun outcome(choice: Int): String = when (choice) {
        1 -> "Pushed fix-login-flake. (Practice: nothing left this phone.)"
        2 -> "Pushed, and git push will not ask again in this session. (Practice: nothing left this phone.)"
        else -> "Claude Code waits for what you type instead. (Practice: nothing left this phone.)"
    }
}

/** A practice notification as the practice fleet draws it: marked, with no actions. */
data class PracticeNotification(
    val sessionId: Long,
    val title: String,
    val body: String,
    /** What a locked phone would show: the session and why, never the question. */
    val publicBody: String,
) {
    /** A practice notification offers no button: not Approve, not anything. */
    val actions: List<String> get() = emptyList()
}

data class PracticeState(
    /** The sample session open, or null for the list. */
    val open: Long? = null,
    /** The answer given to the demo's question, 1 to 3; null while it waits. Never pre-selected. */
    val answered: Int? = null,
)

/** The practice fleet's own state; it starts fresh every time it opens. */
class PracticeFleet {
    private val _state = MutableStateFlow(PracticeState())
    val state: StateFlow<PracticeState> = _state.asStateFlow()

    val sessions: List<SessionRow>
        get() = PracticeFixtures.sessions.map { row ->
            if (row.id == PracticeFixtures.demo.id && _state.value.answered != null) {
                row.copy(claudeStatus = "working", currentActivity = "Pushing fix-login-flake")
            } else row
        }

    fun open(id: Long) = _state.update { it.copy(open = id) }

    fun back(): Boolean {
        if (_state.value.open == null) return false
        _state.update { it.copy(open = null) }
        return true
    }

    /** The person's tap on 1, 2 or 3. Only a tap gets here. */
    fun answer(choice: Int) {
        require(choice in 1..PracticeFixtures.choices.size)
        _state.update { it.copy(answered = choice) }
    }

    fun reset() {
        _state.value = PracticeState()
    }
}
