package io.github.dglearner.shop.lab;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Reads what Ops-Agent reported, through its public API, for scoring only. The lab never sends anything to Ops-Agent.
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

    List<Scoring.Incident> incidentsSince(Instant from) {
        JsonNode page = http.get().uri(u -> u.path("/api/v1/incidents").queryParam("from", from)
                .queryParam("limit", 200).build()).retrieve().body(JsonNode.class);
        List<Scoring.Incident> incidents = new ArrayList<>();
        for (JsonNode i : page.path("items")) {
            List<String> subjects = new ArrayList<>();
            i.path("subjects").forEach(s -> subjects.add(s.asString()));
            incidents.add(new Scoring.Incident(i.path("incident_id").asString(), i.path("title").asString(), subjects,
                    i.path("alert_count").asInt(), Instant.parse(i.path("opened_at").asString()),
                    Instant.parse(i.path("last_alert_at").asString())));
        }
        return incidents;
    }
}
