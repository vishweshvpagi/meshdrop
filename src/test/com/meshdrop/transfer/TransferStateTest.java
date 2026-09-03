package com.meshdrop.transfer;

/**
 * Unit tests for TransferState machine transitions.
 */
public class TransferStateTest {

    public void runAll() throws Exception {
        testValidTransitions();
        testTerminalStatesCannotTransition();
        testTransitionToFailedFromAnyState();
        testInvalidTransitions();
    }

    private void testValidTransitions() {
        assert TransferState.OFFERING.canTransitionTo(TransferState.WAITING_FOR_ACCEPT);
        assert TransferState.WAITING_FOR_ACCEPT.canTransitionTo(TransferState.ACCEPTED);
        assert TransferState.ACCEPTED.canTransitionTo(TransferState.TRANSFERRING);
        assert TransferState.TRANSFERRING.canTransitionTo(TransferState.VERIFYING);
        assert TransferState.VERIFYING.canTransitionTo(TransferState.COMPLETED);
    }

    private void testTerminalStatesCannotTransition() {
        TransferState[] terminals = {
                TransferState.COMPLETED,
                TransferState.REJECTED,
                TransferState.FAILED,
                TransferState.CANCELLED
        };

        for (TransferState term : terminals) {
            assert term.isTerminal();
            assert !term.canTransitionTo(TransferState.TRANSFERRING);
            assert !term.canTransitionTo(TransferState.OFFERING);
            if (term != TransferState.COMPLETED) {
                assert !term.canTransitionTo(TransferState.COMPLETED);
            }
        }
    }

    private void testTransitionToFailedFromAnyState() {
        assert TransferState.OFFERING.canTransitionTo(TransferState.FAILED);
        assert TransferState.WAITING_FOR_ACCEPT.canTransitionTo(TransferState.FAILED);
        assert TransferState.ACCEPTED.canTransitionTo(TransferState.FAILED);
        assert TransferState.TRANSFERRING.canTransitionTo(TransferState.FAILED);
        assert TransferState.VERIFYING.canTransitionTo(TransferState.FAILED);
    }

    private void testInvalidTransitions() {
        assert !TransferState.OFFERING.canTransitionTo(TransferState.COMPLETED);
        assert !TransferState.TRANSFERRING.canTransitionTo(TransferState.OFFERING);
        assert !TransferState.WAITING_FOR_ACCEPT.canTransitionTo(TransferState.VERIFYING);
    }
}
