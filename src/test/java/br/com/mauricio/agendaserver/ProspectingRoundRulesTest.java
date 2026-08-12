package br.com.mauricio.agendaserver;

import org.junit.jupiter.api.Test;

import static br.com.mauricio.agendaserver.ProspectingRoundRules.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProspectingRoundRulesTest {
    @Test void positiveResponseStopsEveryRemainingRound() {
        assertEquals(RESPONDED, ProspectingRoundRules.nextAction(1, 4, 10, 2, 2, 0));
    }

    @Test void waitsOnlyAfterCurrentRoundHasActuallySentAnInvitation() {
        assertEquals(WAIT_FOR_RESPONSE, ProspectingRoundRules.nextAction(0, 0, 5, 3, 3, 0));
        assertEquals(RELEASE_NEXT_ROUND, ProspectingRoundRules.nextAction(0, 0, 5, 0, 0, 2));
    }

    @Test void preservesQueuedWorkAndRecognizesTerminalStates() {
        assertEquals(READY, ProspectingRoundRules.nextAction(0, 2, 5, 0, 0, 0));
        assertEquals(EXHAUSTED, ProspectingRoundRules.nextAction(0, 0, 0, 0, 4, 0));
        assertEquals(FAILED, ProspectingRoundRules.nextAction(0, 0, 0, 0, 0, 4));
        assertEquals(EXHAUSTED, ProspectingRoundRules.nextAction(0, 0, 0, 0, 0, 0));
    }
}
