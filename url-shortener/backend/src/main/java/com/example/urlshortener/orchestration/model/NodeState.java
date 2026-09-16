package com.example.urlshortener.orchestration.model;

/** Per-module lifecycle state. The dashboard colours each grid cell from this value. */
public enum NodeState {
    PENDING,
    RUNNING,
    COMPLETED,
    AWAITING_APPROVAL,
    FAILED
}
