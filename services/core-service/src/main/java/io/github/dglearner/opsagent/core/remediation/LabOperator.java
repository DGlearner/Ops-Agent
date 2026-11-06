package io.github.dglearner.opsagent.core.remediation;

import java.time.Instant;
import java.util.List;

/**
 * What can be looked at and done to the shop lab (docs/agent-design.md §5.3, §13.2). Each method is one fixed thing:
 * nothing here takes a command line, only names and numbers checked by the caller. Never the fault-injection API:
 * clearing an injected fault would be reading the answer key.
 */
public interface LabOperator {

    /**
     * @param state    InnoDB's own: RUNNING, LOCK WAIT, ...
     * @param blocking how many transactions wait on this one; null when it cannot be known (the lab runs MySQL without
     *                 Performance Schema to save memory, and only that table says who blocks whom), never a false 0
     */
    record Transaction(String trxId, long threadId, long ageSeconds, String state, Integer blocking, String user) {

        static final String LOCK_WAIT = "LOCK WAIT";

        boolean waitingForLock() {
            return LOCK_WAIT.equals(state);
        }
    }

    record Switch(String name, boolean on, Instant until) {
    }

    /** running, exited, created, paused, restarting, or missing. */
    String containerState(String name);

    void startContainer(String name);

    /** InnoDB transactions running for at least minAgeSeconds, oldest first. */
    List<Transaction> transactions(long minAgeSeconds);

    void killThread(long threadId);

    List<Switch> switches();

    Switch setSwitch(String name, boolean on, int minutes);

    /** A lab call that failed: the message says which and why. */
    class LabException extends RuntimeException {
        private final boolean maybeDone;

        public LabException(String message, boolean maybeDone) {
            super(message);
            this.maybeDone = maybeDone;
        }

        /** The call may have taken effect anyway (it timed out, say): the receipt is then unknown, not failed. */
        public boolean maybeDone() {
            return maybeDone;
        }
    }
}
