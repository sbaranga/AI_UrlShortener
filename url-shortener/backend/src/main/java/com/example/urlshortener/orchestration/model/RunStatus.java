package com.example.urlshortener.orchestration.model;

/** Status of the run as a whole, as opposed to a single module. */
public enum RunStatus {
    /** No run has been started, or the engine was reset. */
    IDLE,
    RUNNING,
    /** Held at the final gate until a verification key is processed. */
    AWAITING_APPROVAL,
    COMPLETED,
    /** Retries were exhausted, active nodes were halted and the last checkpoint restored. */
    ROLLED_BACK
}
