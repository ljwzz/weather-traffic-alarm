package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves detail links without using the current plan, a latest decision, or a list position as
 * a substitute for historical identity. A decision route remains useful when its occurrence has
 * already been cleaned up; an occurrence route is intentionally strict because it originates
 * from a real ringing instance.
 */
@Singleton
class DecisionDetailRepository @Inject constructor(
    private val decisions: DecisionRepository,
    private val occurrences: OccurrenceRepository,
    private val plans: AlarmPlanRepository,
) {
    suspend fun resolveDecision(decisionId: String): DecisionDetailLookup {
        val decision = decisions.getById(decisionId) ?: return DecisionDetailLookup.DecisionMissing(decisionId)
        val candidates = occurrences.getByPlanId(decision.planId)
            .filter { it.kind == OccurrenceKind.ADVANCE && it.decisionId == decision.decisionId }
            .filter { it.matchesDecisionRoot(decision) }
        val association = when {
            candidates.isEmpty() -> DecisionOccurrenceAssociation.Missing
            candidates.size == 1 -> DecisionOccurrenceAssociation.Exact(candidates.single())
            else -> decision.actualWakeAt.toEpochMillisOrNull()?.let { actualWakeAt ->
                candidates.filter { it.scheduledWakeAt == actualWakeAt }.singleOrNull()
                    ?.let(DecisionOccurrenceAssociation::Exact)
                    ?: DecisionOccurrenceAssociation.Ambiguous(candidates.size)
            } ?: DecisionOccurrenceAssociation.Ambiguous(candidates.size)
        }
        return DecisionDetailLookup.DecisionFound(
            decision = decision,
            plan = plans.getById(decision.planId),
            occurrenceAssociation = association,
        )
    }

    suspend fun resolveOccurrence(occurrenceId: String): DecisionDetailLookup {
        val occurrence = occurrences.getById(occurrenceId)
            ?: return DecisionDetailLookup.OccurrenceUnavailable(occurrenceId, OccurrenceDetailUnavailableReason.OCCURRENCE_MISSING)
        val root = resolveAdvanceRoot(occurrence)
            ?: return DecisionDetailLookup.OccurrenceUnavailable(occurrenceId, OccurrenceDetailUnavailableReason.UNRELATED_INSTANCE)
        val rootDecisionId = root.decisionId
            ?: return DecisionDetailLookup.OccurrenceUnavailable(occurrenceId, OccurrenceDetailUnavailableReason.DECISION_LINK_MISSING)
        if (occurrence.decisionId != null && occurrence.decisionId != rootDecisionId) {
            return DecisionDetailLookup.OccurrenceUnavailable(occurrenceId, OccurrenceDetailUnavailableReason.DECISION_LINK_MISMATCH)
        }
        val decision = decisions.getById(rootDecisionId)
            ?: return DecisionDetailLookup.OccurrenceUnavailable(occurrenceId, OccurrenceDetailUnavailableReason.DECISION_MISSING)
        if (!root.matchesDecisionRoot(decision)) {
            return DecisionDetailLookup.OccurrenceUnavailable(occurrenceId, OccurrenceDetailUnavailableReason.ROOT_IDENTITY_MISMATCH)
        }
        return DecisionDetailLookup.OccurrenceFound(
            decision = decision,
            plan = plans.getById(decision.planId),
            occurrence = occurrence,
            rootAdvance = root,
        )
    }

    private suspend fun resolveAdvanceRoot(start: AlarmOccurrence): AlarmOccurrence? {
        var current = start
        val visited = mutableSetOf<String>()
        while (visited.add(current.occurrenceId)) {
            when (current.kind) {
                OccurrenceKind.ADVANCE -> return current
                OccurrenceKind.SNOOZE -> {
                    val parentId = current.parentOccurrenceId ?: return null
                    val parent = occurrences.getById(parentId) ?: return null
                    if (parent.planId != start.planId) return null
                    current = parent
                }
                OccurrenceKind.REGULAR -> return null
            }
        }
        return null
    }

    private fun AlarmOccurrence.matchesDecisionRoot(decision: AlarmDecision): Boolean =
        kind == OccurrenceKind.ADVANCE &&
            decisionId == decision.decisionId &&
            planId == decision.planId &&
            planRevision == decision.planRevision &&
            targetDate == decision.targetDate

    private fun String?.toEpochMillisOrNull(): Long? = this?.toLongOrNull()
        ?: this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

sealed interface DecisionDetailLookup {
    data class DecisionFound(
        val decision: AlarmDecision,
        val plan: AlarmPlan?,
        val occurrenceAssociation: DecisionOccurrenceAssociation,
    ) : DecisionDetailLookup

    data class OccurrenceFound(
        val decision: AlarmDecision,
        val plan: AlarmPlan?,
        /** The exact ringing/snooze instance requested by the caller. */
        val occurrence: AlarmOccurrence,
        /** The ADVANCE root that has the persisted decision association. */
        val rootAdvance: AlarmOccurrence,
    ) : DecisionDetailLookup

    data class DecisionMissing(val decisionId: String) : DecisionDetailLookup

    data class OccurrenceUnavailable(
        val occurrenceId: String,
        val reason: OccurrenceDetailUnavailableReason,
    ) : DecisionDetailLookup
}

sealed interface DecisionOccurrenceAssociation {
    data class Exact(val occurrence: AlarmOccurrence) : DecisionOccurrenceAssociation
    data object Missing : DecisionOccurrenceAssociation
    data class Ambiguous(val count: Int) : DecisionOccurrenceAssociation
}

enum class OccurrenceDetailUnavailableReason {
    OCCURRENCE_MISSING,
    UNRELATED_INSTANCE,
    DECISION_LINK_MISSING,
    DECISION_LINK_MISMATCH,
    DECISION_MISSING,
    ROOT_IDENTITY_MISMATCH,
}
