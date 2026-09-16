package com.example.urlshortener.orchestration.model;

/** The six pipeline modules the orchestrator drives, in declaration order. */
public enum ModuleId {
    REQUIREMENTS("Requirements"),
    ARCHITECTURE("Architecture"),
    IMPLEMENTATION("Implementation"),
    DOCUMENTATION("Documentation"),
    TESTING("Testing"),
    RELEASE_READINESS("Release Readiness");

    private final String displayName;

    ModuleId(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
