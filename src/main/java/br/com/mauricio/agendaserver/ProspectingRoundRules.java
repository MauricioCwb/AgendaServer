package br.com.mauricio.agendaserver;

final class ProspectingRoundRules {
    private ProspectingRoundRules() { }

    static Action nextAction(int registered, int queued, int waiting, int sentCurrentRound,
                             int sent, int failures) {
        if (registered > 0) return Action.RESPONDED;
        if (queued > 0) return Action.READY;
        if (waiting > 0 && sentCurrentRound > 0) return Action.WAIT_FOR_RESPONSE;
        if (waiting > 0) return Action.RELEASE_NEXT_ROUND;
        if (sent > 0) return Action.EXHAUSTED;
        if (failures > 0) return Action.FAILED;
        return Action.EXHAUSTED;
    }

    enum Action {
        RESPONDED,
        READY,
        WAIT_FOR_RESPONSE,
        RELEASE_NEXT_ROUND,
        EXHAUSTED,
        FAILED
    }
}
