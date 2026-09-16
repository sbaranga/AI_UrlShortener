package com.example.urlshortener.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.example.urlshortener.model.UrlMapping;
import com.example.urlshortener.repository.UrlRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.base-url=http://localhost:8080", "app.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class UrlControllerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UrlRepository repository;

    @BeforeEach
    void resetDatabase() {
        repository.deleteAll();
    }

    @Test
    void shortenRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shortenReturnsACreatedLink() throws Exception {
        String body = mockMvc.perform(post("/api/v1/shorten")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/a/very/long/path?q=1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/a/very/long/path?q=1"))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("shortCode").asText()).hasSize(7);
        assertThat(json.get("shortUrl").asText())
                .isEqualTo("http://localhost:8080/" + json.get("shortCode").asText());
    }

    @Test
    void shortenHonoursACustomAlias() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"customAlias\":\"my-link\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("my-link"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/my-link"));
    }

    @Test
    void shortenRejectsAnAliasThatIsAlreadyTaken() throws Exception {
        createLink("https://example.com", "taken");

        mockMvc.perform(post("/api/v1/shorten")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://other.example\",\"customAlias\":\"taken\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shortenRejectsAReservedAlias() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"customAlias\":\"api\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shortenRejectsNonHttpSchemes() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("http")));
    }

    @Test
    void shortenRejectsAMissingUrl() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.url").exists());
    }

    @Test
    void redirectSendsTheBrowserToTheTargetAndCountsTheClick() throws Exception {
        createLink("https://example.com/docs", "go-docs");

        mockMvc.perform(get("/go-docs"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/docs"))
                .andExpect(header().string("Cache-Control", "no-store"));

        mockMvc.perform(get("/api/v1/urls/go-docs")).andExpect(jsonPath("$.clickCount").value(1));
    }

    @Test
    void redirectReturnsNotFoundForAnUnknownCode() throws Exception {
        mockMvc.perform(get("/nosuch1")).andExpect(status().isNotFound());
    }

    @Test
    void redirectReturnsGoneForAnExpiredLink() throws Exception {
        // The API's minimum lifetime is one day, so insert an already-expired row directly.
        Instant now = Instant.now();
        repository.saveAndFlush(
                new UrlMapping("expired", "https://example.com", now.minusSeconds(120), now.minusSeconds(60)));

        mockMvc.perform(get("/expired")).andExpect(status().isGone());
    }

    @Test
    void statsReturnsNotFoundForAnUnknownCode() throws Exception {
        mockMvc.perform(get("/api/v1/urls/nosuch1")).andExpect(status().isNotFound());
    }

    @Test
    void analyticsReturnsNewestFirst() throws Exception {
        createLink("https://first.example", "first1");
        createLink("https://second.example", "second1");

        mockMvc.perform(get("/api/v1/analytics").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].shortCode").value("second1"))
                .andExpect(jsonPath("$.items[1].shortCode").value("first1"));
    }

    @Test
    void analyticsPagesTheResults() throws Exception {
        createLink("https://first.example", "first1");
        createLink("https://second.example", "second1");
        createLink("https://third.example", "third1");

        mockMvc.perform(get("/api/v1/analytics").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].shortCode").value("first1"));
    }

    @Test
    void summaryIsEmptyWhenThereAreNoLinks() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLinks").value(0))
                .andExpect(jsonPath("$.totalClicks").value(0))
                .andExpect(jsonPath("$.mostClicked").doesNotExist());
    }

    @Test
    void summaryAggregatesClicksAcrossEveryLink() throws Exception {
        createLink("https://busy.example", "busy01");
        createLink("https://quiet.example", "quiet1");

        mockMvc.perform(get("/busy01")).andExpect(status().isFound());
        mockMvc.perform(get("/busy01")).andExpect(status().isFound());
        mockMvc.perform(get("/quiet1")).andExpect(status().isFound());

        mockMvc.perform(get("/api/v1/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLinks").value(2))
                .andExpect(jsonPath("$.totalClicks").value(3))
                .andExpect(jsonPath("$.activeLinks").value(2))
                .andExpect(jsonPath("$.expiredLinks").value(0))
                .andExpect(jsonPath("$.mostClicked.shortCode").value("busy01"))
                .andExpect(jsonPath("$.mostClicked.clickCount").value(2));
    }

    @Test
    void summaryCountsAnExpiredLinkSeparatelyFromActiveOnes() throws Exception {
        createLink("https://example.com", "active1");
        Instant now = Instant.now();
        repository.saveAndFlush(
                new UrlMapping("expired", "https://example.com", now.minusSeconds(120), now.minusSeconds(60)));

        mockMvc.perform(get("/api/v1/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLinks").value(2))
                .andExpect(jsonPath("$.activeLinks").value(1))
                .andExpect(jsonPath("$.expiredLinks").value(1));
    }

    @Test
    void deleteRemovesTheLink() throws Exception {
        createLink("https://example.com", "delete-me");

        mockMvc.perform(delete("/api/v1/urls/delete-me")
                .with(httpBasic("admin", "change-me")))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/urls/delete-me")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/urls/delete-me")
                .with(httpBasic("admin", "change-me")))
            .andExpect(status().isNotFound());
        }

        @Test
        void deleteRequiresAuthentication() throws Exception {
        createLink("https://example.com", "protected");

        mockMvc.perform(delete("/api/v1/urls/protected")).andExpect(status().isUnauthorized());
    }

    private void createLink(String url, String alias) throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\",\"customAlias\":\"" + alias + "\"}"))
                .andExpect(status().isCreated());
    }
}
