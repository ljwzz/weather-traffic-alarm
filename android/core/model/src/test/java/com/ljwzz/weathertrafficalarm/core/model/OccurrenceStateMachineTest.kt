package com.ljwzz.weathertrafficalarm.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OccurrenceStateMachineTest {

    @Test
    fun defaultRegisteredCanTransitionToAdvanced() {
        assertTrue(OccurrenceStateMachine.canTransition(OccurrenceState.DEFAULT_REGISTERED, OccurrenceState.ADVANCED))
    }

    @Test
    fun defaultRegisteredCanTransitionToFiring() {
        assertTrue(OccurrenceStateMachine.canTransition(OccurrenceState.DEFAULT_REGISTERED, OccurrenceState.FIRING))
    }

    @Test
    fun defaultRegisteredCanTransitionToCancelled() {
        assertTrue(OccurrenceStateMachine.canTransition(OccurrenceState.DEFAULT_REGISTERED, OccurrenceState.CANCELLED))
    }

    @Test
    fun defaultRegisteredCannotTransitionToSnoozed() {
        assertFalse(OccurrenceStateMachine.canTransition(OccurrenceState.DEFAULT_REGISTERED, OccurrenceState.SNOOZED))
    }

    @Test
    fun firingCanTransitionToSnoozed() {
        assertTrue(OccurrenceStateMachine.canTransition(OccurrenceState.FIRING, OccurrenceState.SNOOZED))
    }

    @Test
    fun firingCanTransitionToDismissed() {
        assertTrue(OccurrenceStateMachine.canTransition(OccurrenceState.FIRING, OccurrenceState.DISMISSED))
    }

    @Test
    fun firingCanTransitionToMissed() {
        assertTrue(OccurrenceStateMachine.canTransition(OccurrenceState.FIRING, OccurrenceState.MISSED))
    }

    @Test
    fun firingCannotTransitionToAdvanced() {
        assertFalse(OccurrenceStateMachine.canTransition(OccurrenceState.FIRING, OccurrenceState.ADVANCED))
    }

    @Test
    fun snoozedCanTransitionToFiring() {
        assertTrue(OccurrenceStateMachine.canTransition(OccurrenceState.SNOOZED, OccurrenceState.FIRING))
    }

    @Test
    fun snoozedCannotTransitionToAdvanced() {
        assertFalse(OccurrenceStateMachine.canTransition(OccurrenceState.SNOOZED, OccurrenceState.ADVANCED))
    }

    @Test
    fun dismissedIsTerminal() {
        assertTrue(OccurrenceStateMachine.isTerminal(OccurrenceState.DISMISSED))
        assertTrue(OccurrenceStateMachine.isTerminal(OccurrenceState.MISSED))
        assertTrue(OccurrenceStateMachine.isTerminal(OccurrenceState.CANCELLED))
    }

    @Test
    fun defaultRegisteredIsNotTerminal() {
        assertFalse(OccurrenceStateMachine.isTerminal(OccurrenceState.DEFAULT_REGISTERED))
        assertFalse(OccurrenceStateMachine.isTerminal(OccurrenceState.ADVANCED))
        assertFalse(OccurrenceStateMachine.isTerminal(OccurrenceState.FIRING))
        assertFalse(OccurrenceStateMachine.isTerminal(OccurrenceState.SNOOZED))
    }

    @Test
    fun transitionReturnsTargetForValidTransition() {
        val result = OccurrenceStateMachine.transition(OccurrenceState.DEFAULT_REGISTERED, OccurrenceState.FIRING)
        assertEquals(OccurrenceState.FIRING, result)
    }

    @Test
    fun transitionReturnsCurrentForSameState() {
        val result = OccurrenceStateMachine.transition(OccurrenceState.DEFAULT_REGISTERED, OccurrenceState.DEFAULT_REGISTERED)
        assertEquals(OccurrenceState.DEFAULT_REGISTERED, result)
    }

    @Test
    fun transitionReturnsNullForInvalidTransition() {
        val result = OccurrenceStateMachine.transition(OccurrenceState.DEFAULT_REGISTERED, OccurrenceState.SNOOZED)
        assertNull(result)
    }
}
