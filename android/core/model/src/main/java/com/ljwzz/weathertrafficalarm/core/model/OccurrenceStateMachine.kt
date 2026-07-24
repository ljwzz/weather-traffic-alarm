package com.ljwzz.weathertrafficalarm.core.model

class OccurrenceStateMachine {

    companion object {
        private val TRANSITIONS: Map<OccurrenceState, Set<OccurrenceState>> = mapOf(
            OccurrenceState.DEFAULT_REGISTERED to setOf(
                OccurrenceState.ADVANCED,
                OccurrenceState.FIRING,
                OccurrenceState.CANCELLED,
            ),
            OccurrenceState.ADVANCED to setOf(
                OccurrenceState.FIRING,
                OccurrenceState.CANCELLED,
            ),
            OccurrenceState.FIRING to setOf(
                OccurrenceState.SNOOZED,
                OccurrenceState.DISMISSED,
                OccurrenceState.MISSED,
            ),
            OccurrenceState.SNOOZED to setOf(
                OccurrenceState.FIRING,
                OccurrenceState.DISMISSED,
                OccurrenceState.CANCELLED,
            ),
            OccurrenceState.DISMISSED to emptySet(),
            OccurrenceState.MISSED to emptySet(),
            OccurrenceState.CANCELLED to emptySet(),
        )

        private val TERMINAL_STATES: Set<OccurrenceState> = setOf(
            OccurrenceState.DISMISSED,
            OccurrenceState.MISSED,
            OccurrenceState.CANCELLED,
        )

        fun canTransition(from: OccurrenceState, to: OccurrenceState): Boolean =
            to in (TRANSITIONS[from] ?: emptySet())

        fun isTerminal(state: OccurrenceState): Boolean = state in TERMINAL_STATES

        fun transition(current: OccurrenceState, target: OccurrenceState): OccurrenceState? {
            if (target == current) return current  // Idempotent
            return if (canTransition(current, target)) target else null
        }
    }
}
