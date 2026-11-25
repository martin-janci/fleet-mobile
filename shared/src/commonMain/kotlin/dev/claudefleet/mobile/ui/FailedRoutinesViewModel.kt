package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.RoutineActions
import dev.claudefleet.mobile.model.FailingRoutine
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The routines whose newest run failed and which this person may change
 * (`routines { failing }`, `may_change`): the Inbox's Failed rows, counted
 * on its badge as the desktop's rail counts them (`failingCount`, review
 * w09-7). A failure the phone notified about is then a row to open, not
 * only a notification. Asked only of a hub that serves the action; one that
 * refuses leaves the list empty.
 */
class FailedRoutinesViewModel(
    private val actions: RoutineActions,
    private val scope: CoroutineScope,
    /** The hub's tools; null asks regardless (tests). */
    private val capabilities: StateFlow<HubCapabilities>? = null,
) {
    private val _state = MutableStateFlow<List<FailingRoutine>>(emptyList())
    val state: StateFlow<List<FailingRoutine>> = _state.asStateFlow()

    fun refresh(): Job = scope.launch {
        if (capabilities?.value?.has(HubCapabilities.ROUTINES, FAILING) == false) {
            _state.value = emptyList()
            return@launch
        }
        try {
            _state.value = failedRoutineRows(actions.failing())
        } catch (e: CancellationException) {
            throw e
        } catch (_: HubError.Tool) {
            // An older hub, or a device the hub serves no routines: nothing to show.
            _state.value = emptyList()
        } catch (_: Throwable) {
            // Keep the last list: a dropped connection is not "nothing failed".
        }
    }

    /**
     * Read again on each of [changes] (a routine's run moved) and every
     * [every] meanwhile: the rows and their share of the badge follow a
     * failure, a retry that passed and a routine turned off (review w05-7).
     * Runs until cancelled.
     */
    suspend fun follow(changes: Flow<Unit>, every: Duration = 15.minutes) {
        merge(changes, flow { while (true) { delay(every); emit(Unit) } }).collect { refresh().join() }
    }

    private companion object {
        const val FAILING = "failing"
    }
}

/** The Inbox's Failed routine rows: the ones this person may change, newest failure first. */
fun failedRoutineRows(failing: List<FailingRoutine>): List<FailingRoutine> =
    failing.filter { it.actsHere }.sortedByDescending { it.run.startedAt }
