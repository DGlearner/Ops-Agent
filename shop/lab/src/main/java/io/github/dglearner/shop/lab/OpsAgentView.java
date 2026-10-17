package io.github.dglearner.shop.lab;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Reads what Ops-Agent reported, through its public API: alerts, incidents and the agent's analyses of them. For
 * scoring and showing only; the lab never sends anything to Ops-Agent.
 */
@Component
class OpsAgentView {

    private final RestClient http;

    OpsAgentView(LabProperties properties) {
        this.http = RestClient.builder().baseUrl(properties.opsAgentUrl()).build();
    }

    List<Scoring.Alert> alertsSince(Instant from) {
        List<Scoring.Alert> alerts = new ArrayList<>();
        String cursor = null;
        do {
            String after = cursor;
            JsonNode page = http.get().uri(u -> u.path("/api/v1/alerts").queryParam("from", from)
                    .queryParam("limit", 200).queryParamIfPresent("cursor", Optional.ofNullable(after))
                    .build()).retrieve().body(JsonNode.class);
            for (JsonNode a : page.path("items")) {
                alerts.add(new Scoring.Alert(a.path("alert_id").asString(), a.path("rule_id").asString(),
                        a.path("service").asString(), a.path("summary").asString(),
                        Instant.parse(a.path("created_at").asString())));
            }
            cursor = page.path("next_cursor").isMissingNode() ? null : page.path("next_cursor").asString();
        } while (cursor != null && alerts.size() < 2000);
        return alerts;
    }

    /** Newest first, each with its analyses. */
    List<Scoring.Incident> incidentsSince(Instant from) {
        JsonNode page = http.get().uri(u -> u.path("/api/v1/incidents").queryParam("from", from)
                .queryParam("limit", 200).build()).retrieve().body(JsonNode.class);
        List<Scoring.Incident> incidents = new ArrayList<>();
        for (JsonNode i : page.path("items")) {
            String id = i.path("incident_id").asString();
            incidents.add(new Scoring.Incident(id, i.path("title").asString(), i.path("status").asString(),
                    strings(i.path("subjects")), i.path("alert_count").asInt(),
                    Instant.parse(i.path("opened_at").asString()), Instant.parse(i.path("last_alert_at").asString()),
                    analyses(id)));
        }
        return incidents;
    }

    private List<Scoring.Analysis> analyses(String incidentId) {
        JsonNode list = http.get().uri("/api/v1/incidents/{id}/analyses", incidentId).retrieve().body(JsonNode.class);
        List<Scoring.Analysis> analyses = new ArrayList<>();
        for (JsonNode a : list) {
            List<Scoring.Hypothesis> hypotheses = new ArrayList<>();
            for (JsonNode h : a.path("hypotheses")) {
                hypotheses.add(new Scoring.Hypothesis(h.path("component").asString(), h.path("cause").asString(),
                        h.path("confidence").asString(), h.path("evidence").size()));
            }
            analyses.add(new Scoring.Analysis(a.path("analysis_id").asString(), a.path("status").asString(),
                    a.path("requested_by").asString(), Instant.parse(a.path("requested_at").asString()),
                    text(a, "model"), a.has("uncertain") ? a.path("uncertain").asBoolean() : null, text(a, "summary"),
                    hypotheses, strings(a.path("next_steps")), a.path("rejected_evidence").size(),
                    a.has("tool_calls") ? a.path("tool_calls").asInt() : null,
                    a.has("duration_ms") ? a.path("duration_ms").asLong() : null, text(a, "error")));
        }
        return analyses;
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) ? node.path(field).asString() : null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }
}
