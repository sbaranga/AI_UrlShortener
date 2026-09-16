package com.example.urlshortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import com.example.urlshortener.orchestration.model.ModuleId;
import com.example.urlshortener.orchestration.model.NodeState;

@Entity
@Table(name = "lineage_entry")
public class LineageEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sequence_number", nullable = false, unique = true)
    private long sequenceNumber;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "event", nullable = false, length = 64)
    private String event;

    @Enumerated(EnumType.STRING)
    @Column(name = "module", length = 32)
    private ModuleId module;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 32)
    private NodeState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", length = 32)
    private NodeState toState;

    @Column(name = "message", nullable = false, length = 2048)
    private String message;

    protected LineageEntryEntity() {}

    public LineageEntryEntity(
            long sequenceNumber,
            Instant occurredAt,
            String runId,
            String event,
            ModuleId module,
            NodeState fromState,
            NodeState toState,
            String message) {
        this.sequenceNumber = sequenceNumber;
        this.occurredAt = occurredAt;
        this.runId = runId;
        this.event = event;
        this.module = module;
        this.fromState = fromState;
        this.toState = toState;
        this.message = message;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getEvent() {
        return event;
    }

    public ModuleId getModule() {
        return module;
    }

    public NodeState getFromState() {
        return fromState;
    }

    public NodeState getToState() {
        return toState;
    }

    public String getMessage() {
        return message;
    }
}
