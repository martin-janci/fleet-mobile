package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.WizardState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Wizards that resume on the person's other device (`wizard_state`, hub
 * contract 17): read the one left half-way, keep this one's place, forget it.
 * Offered only where the hub lists the tool.
 */
interface WizardDrafts {
    suspend fun get(kind: String): WizardState?
    suspend fun save(kind: String, step: Int, answers: JsonObject, label: String?)
    suspend fun clear(kind: String)
}

/** [WizardDrafts] against the paired hub. */
class HubWizardDrafts(private val session: AppSession) : WizardDrafts {
    override suspend fun get(kind: String): WizardState? = session.withClient { it.wizardGet(kind) }
    override suspend fun save(kind: String, step: Int, answers: JsonObject, label: String?) =
        session.withClient { it.wizardSave(kind, step, answers, label) }
    override suspend fun clear(kind: String) = session.withClient { it.wizardClear(kind) }
}

/**
 * [block], with a failure dropped: a draft that could not be read or kept
 * costs only the resume offer, never the wizard itself.
 */
suspend fun <T> WizardDrafts.quietly(block: suspend WizardDrafts.() -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    null
}
