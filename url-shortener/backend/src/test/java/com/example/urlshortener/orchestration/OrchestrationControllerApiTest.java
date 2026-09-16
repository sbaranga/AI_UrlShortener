package com.example.urlshortener.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.urlshortener.repository.LineageEntryRepository;
import com.example.urlshortener.repository.TelemetryCounterRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives the engine through the real HTTP surface. {@code module-latency-ms=0} keeps the modules
 * instant, and each command response is asserted rather than polling, so the test does not sleep.
 */
@SpringBootTest(
        properties = {
            "app.rate-limit.enabled=false",
            "app.orchestration.module-latency-ms=0",
            "app.orchestration.worker-threads=2"
        })
@AutoConfigureMockMvc
class OrchestrationControllerApiTest {

    private static final String BASE = "/api/v1/orchestration";

    @Autowired
    private MockMvc mockMvc;

        @Autowired
        private LineageEntryRepository lineageRepository;

        @Autowired
        private TelemetryCounterRepository telemetryRepository;

        private MockHttpServletRequestBuilder command(String path) {
                return post(path).with(httpBasic("admin", "change-me"));
        }

    @BeforeEach
    void resetEngine() throws Exception {
                lineageRepository.deleteAll();
                telemetryRepository.deleteAll();
        mockMvc.perform(command(BASE + "/reset")).andExpect(status().isOk());
    }

    /**
     * Waits for the worker pool to settle. The engine runs its modules on a real executor here, so
     * a command can return before the graph has finished moving.
     */
    private void awaitStatus(String expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String body = mockMvc.perform(get(BASE + "/state"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            if (body.contains("\"status\":\"" + expected + "\"")) {
                return;
            }
            Thread.sleep(20);
        }
        mockMvc.perform(get(BASE + "/state")).andExpect(jsonPath("$.status").value(expected));
    }

    @Test
    void stateStartsIdleWithSixPendingModules() throws Exception {
        mockMvc.perform(get(BASE + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.nodes.length()").value(6))
                .andExpect(jsonPath("$.nodes[0].id").value("REQUIREMENTS"))
                .andExpect(jsonPath("$.nodes[5].id").value("RELEASE_READINESS"))
                .andExpect(jsonPath("$.nodes[*].state", Matchers.everyItem(Matchers.is("PENDING"))))
                .andExpect(jsonPath("$.barrier.guarded").value("TESTING"))
                .andExpect(jsonPath("$.approval.module").value("RELEASE_READINESS"))
                .andExpect(jsonPath("$.approval.pending").value(false));
    }

        @Test
        void governanceCommandsRequireAuthentication() throws Exception {
                mockMvc.perform(post(BASE + "/start")).andExpect(status().isUnauthorized());
        }

        @Test
        void lineageEventsArePersistedInTheDatabase() throws Exception {
                mockMvc.perform(command(BASE + "/start")).andExpect(status().isOk());

                assertThat(lineageRepository.findAllByOrderBySequenceNumberAsc())
                                .anySatisfy(entry -> {
                                        org.assertj.core.api.Assertions.assertThat(entry.getEvent()).isEqualTo("RUN_STARTED");
                                        org.assertj.core.api.Assertions.assertThat(entry.getRunId()).isNotBlank();
                                });
        }

        @Test
        void telemetryCountersArePersistedInTheDatabase() throws Exception {
                mockMvc.perform(command(BASE + "/start")).andExpect(status().isOk());

                assertThat(telemetryRepository.findAll()).isNotEmpty();
                assertThat(telemetryRepository.findById(com.example.urlshortener.orchestration.model.ModuleId.REQUIREMENTS))
                                .get()
                                .extracting(counter -> counter.getAttempts())
                                .isEqualTo(1L);
        }

    @Test
    void exposesTheForkAndTheBarrierInTheGraphDefinition() throws Exception {
        mockMvc.perform(get(BASE + "/state"))
                .andExpect(status().isOk())
                // Implementation and Documentation both depend only on Architecture: that is the fork.
                .andExpect(jsonPath("$.nodes[2].dependsOn").value(Matchers.contains("ARCHITECTURE")))
                .andExpect(jsonPath("$.nodes[3].dependsOn").value(Matchers.contains("ARCHITECTURE")))
                .andExpect(jsonPath("$.nodes[2].channel").value("build"))
                .andExpect(jsonPath("$.nodes[3].channel").value("docs"))
                // Testing has two incoming edges: that is the join the barrier guards.
                .andExpect(jsonPath("$.nodes[4].join").value(true))
                .andExpect(jsonPath("$.nodes[4].dependsOn.length()").value(2))
                .andExpect(jsonPath("$.nodes[5].approvalGate").value(true));
    }

    @Test
    void runsToTheApprovalGateAndStopsThere() throws Exception {
        mockMvc.perform(command(BASE + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.runId").value(Matchers.not("none")));

        awaitStatus("AWAITING_APPROVAL");

        mockMvc.perform(get(BASE + "/state"))
                .andExpect(jsonPath("$.approval.pending").value(true))
                .andExpect(jsonPath("$.nodes[5].state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.nodes[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.nodes[4].state").value("COMPLETED"))
                .andExpect(jsonPath("$.barrier.open").value(true));
    }

    @Test
    void approvalRequiresAVerificationKey() throws Exception {
        mockMvc.perform(command(BASE + "/start")).andExpect(status().isOk());
        awaitStatus("AWAITING_APPROVAL");

        mockMvc.perform(command(BASE + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"sbaranga\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.verificationKey").exists());

        mockMvc.perform(command(BASE + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationKey\":\"RELEASE-1\",\"approver\":\"sbaranga\"}"))
                .andExpect(status().isOk());

        awaitStatus("COMPLETED");

        mockMvc.perform(get(BASE + "/state"))
                .andExpect(jsonPath("$.nodes[*].state", Matchers.everyItem(Matchers.is("COMPLETED"))))
                .andExpect(jsonPath("$.telemetry.totalSuccesses").value(6))
                .andExpect(jsonPath("$.telemetry.successRate").value(1.0));
    }

    @Test
    void approvingWithNothingPendingIsRejected() throws Exception {
        mockMvc.perform(command(BASE + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationKey\":\"RELEASE-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("No approval is pending")));
    }

    @Test
    void startingTwiceIsAConflict() throws Exception {
        mockMvc.perform(command(BASE + "/start")).andExpect(status().isOk());

        // The first run is either still going or parked at the gate; both refuse a second start.
        mockMvc.perform(command(BASE + "/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void aPersistentAnomalyRollsTheRunBackAndAStepResumesIt() throws Exception {
        mockMvc.perform(command(BASE + "/start")).andExpect(status().isOk());
        mockMvc.perform(command(BASE + "/failures/RELEASE_READINESS").param("persistent", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.armedFailures").value(Matchers.contains("RELEASE_READINESS")));

        awaitStatus("AWAITING_APPROVAL");
        mockMvc.perform(command(BASE + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationKey\":\"RELEASE-1\"}"))
                .andExpect(status().isOk());

        awaitStatus("ROLLED_BACK");

        mockMvc.perform(get(BASE + "/state"))
                .andExpect(jsonPath("$.nodes[5].state").value("FAILED"))
                .andExpect(jsonPath("$.nodes[5].attempts").value(3))
                .andExpect(jsonPath("$.telemetry.totalFailures").value(3));

        // The rollback cleared the armed anomaly, so resuming now gets through.
        mockMvc.perform(command(BASE + "/step")).andExpect(status().isOk());
        awaitStatus("AWAITING_APPROVAL");
        mockMvc.perform(command(BASE + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationKey\":\"RELEASE-2\"}"))
                .andExpect(status().isOk());
        awaitStatus("COMPLETED");
    }

    @Test
    void injectingAFailureBeforeStartingIsRejected() throws Exception {
        mockMvc.perform(command(BASE + "/failures/TESTING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Start the run")));
    }

    @Test
    void anUnknownModuleIsRejected() throws Exception {
        mockMvc.perform(command(BASE + "/failures/NOT_A_MODULE")).andExpect(status().isBadRequest());
    }

    @Test
    void lineageRecordsTheRunChronologically() throws Exception {
        mockMvc.perform(command(BASE + "/start")).andExpect(status().isOk());
        awaitStatus("AWAITING_APPROVAL");

        mockMvc.perform(get(BASE + "/lineage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event").value("RUN_RESET"))
                .andExpect(jsonPath("$[1].event").value("RUN_STARTED"))
                .andExpect(jsonPath("$[*].event", Matchers.hasItems(
                        "CHECKPOINT_TAKEN",
                        "NODE_STARTED",
                        "NODE_COMPLETED",
                        "FORK_DISPATCHED",
                        "BARRIER_OPENED",
                        "APPROVAL_REQUESTED")))
                .andExpect(jsonPath("$[*].at", Matchers.everyItem(Matchers.notNullValue())));
    }

    @Test
    void lineageLimitIsClampedToASaneRange() throws Exception {
        mockMvc.perform(command(BASE + "/start")).andExpect(status().isOk());
        awaitStatus("AWAITING_APPROVAL");

        mockMvc.perform(get(BASE + "/lineage").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get(BASE + "/lineage").param("limit", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void resetReturnsEveryModuleToPending() throws Exception {
        mockMvc.perform(command(BASE + "/start")).andExpect(status().isOk());
        awaitStatus("AWAITING_APPROVAL");

        mockMvc.perform(command(BASE + "/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.runId").value("none"))
                .andExpect(jsonPath("$.nodes[*].state", Matchers.everyItem(Matchers.is("PENDING"))))
                .andExpect(jsonPath("$.telemetry.totalAttempts").value(0));
    }
}
