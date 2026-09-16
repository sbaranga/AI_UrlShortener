package com.example.urlshortener.orchestration;

import com.example.urlshortener.orchestration.LineageLedger.LineageEntry;
import com.example.urlshortener.orchestration.dto.ApprovalRequest;
import com.example.urlshortener.orchestration.dto.OrchestrationState;
import com.example.urlshortener.orchestration.model.ModuleId;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control surface for the governance dashboard. Every command returns the whole state so the UI
 * repaints from one authoritative response instead of patching its own copy.
 */
@RestController
@RequestMapping("/api/v1/orchestration")
public class OrchestrationController {

    private static final int MAX_LEDGER_PAGE = 500;

    private final WorkflowEngine engine;
    private final LineageLedger ledger;

    public OrchestrationController(WorkflowEngine engine, LineageLedger ledger) {
        this.engine = engine;
        this.ledger = ledger;
    }

    @GetMapping("/state")
    public OrchestrationState state() {
        return engine.state();
    }

    @PostMapping("/start")
    public OrchestrationState start() {
        return engine.start();
    }

    /** Manual execution step; also what resumes a run after a rollback. */
    @PostMapping("/step")
    public OrchestrationState step() {
        return engine.step();
    }

    /**
     * Arms a simulated anomaly. {@code persistent=false} fails one attempt so the bounded retry
     * recovers it; {@code persistent=true} fails every attempt and drives the automated rollback.
     */
    @PostMapping("/failures/{module}")
    public OrchestrationState injectFailure(
            @PathVariable ModuleId module, @RequestParam(defaultValue = "false") boolean persistent) {
        return engine.injectFailure(module, persistent);
    }

    @PostMapping("/approve")
    public OrchestrationState approve(@Valid @RequestBody ApprovalRequest request) {
        return engine.approve(request.verificationKey(), request.approver());
    }

    @PostMapping("/reset")
    public OrchestrationState reset() {
        return engine.reset();
    }

    /** Chronological lineage records, oldest first. */
    @GetMapping("/lineage")
    public List<LineageEntry> lineage(@RequestParam(defaultValue = "200") int limit) {
        return ledger.latest(Math.min(MAX_LEDGER_PAGE, Math.max(1, limit)));
    }
}
