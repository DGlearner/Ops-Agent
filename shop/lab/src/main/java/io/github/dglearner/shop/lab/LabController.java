package io.github.dglearner.shop.lab;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** The API behind the lab page. */
@RestController
@RequestMapping("/api/lab")
class LabController {

    record TrafficRequest(double rate, Double duplicateRate, Double declineRate) {
    }

    record FaultRequest(Integer durationS) {
    }

    record FlashSaleRequest(String sku, int stock, double rate, int durationS) {
    }

    private final Traffic traffic;
    private final Faults faults;
    private final GroundTruth groundTruth;
    private final OpsAgentView opsAgent;

    LabController(Traffic traffic, Faults faults, GroundTruth groundTruth, OpsAgentView opsAgent) {
        this.traffic = traffic;
        this.faults = faults;
        this.groundTruth = groundTruth;
        this.opsAgent = opsAgent;
    }

    @GetMapping("/state")
    Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("traffic", traffic.status());
        state.put("faults", faults.catalog());
        state.put("episodes", groundTruth.episodes().stream()
                .sorted(Comparator.comparing(Episode::startedAt).reversed()).limit(30).toList());
        return state;
    }

    @PostMapping("/traffic")
    void startTraffic(@RequestBody TrafficRequest request) {
        if (request.rate() <= 0 || request.rate() > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rate must be between 0 and 300 orders/s");
        }
        traffic.start(request.rate(), request.duplicateRate() == null ? 0.05 : request.duplicateRate(),
                request.declineRate() == null ? 0.05 : request.declineRate());
    }

    @DeleteMapping("/traffic")
    void stopTraffic() {
        traffic.stop();
    }

    @PostMapping("/faults/{name}")
    Episode inject(@PathVariable String name, @RequestBody(required = false) FaultRequest request) {
        int seconds = request == null || request.durationS() == null ? 120 : request.durationS();
        return faults.inject(name, Duration.ofSeconds(seconds));
    }

    @DeleteMapping("/faults/{name}")
    Episode clear(@PathVariable String name) {
        return faults.clear(name);
    }

    @PostMapping("/flash-sale")
    Episode flashSale(@RequestBody FlashSaleRequest request) {
        if (request.stock() < 0 || request.rate() <= 0 || request.rate() > 300 || request.durationS() <= 0
                || request.durationS() > 600) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "stock >= 0, rate up to 300 orders/s and duration up to 600 s");
        }
        return faults.flashSale(request.sku() == null ? "SKU-2001" : request.sku(), request.stock(), request.rate(),
                Duration.ofSeconds(request.durationS()));
    }

    /** The episodes scored against what Ops-Agent reported since the first of them. */
    @GetMapping("/evaluation")
    Map<String, Object> evaluation() {
        List<Episode> episodes = groundTruth.episodes();
        Map<String, Object> result = new LinkedHashMap<>();
        if (episodes.isEmpty()) {
            result.put("message", "Nothing done yet");
            return result;
        }
        Instant from = episodes.stream().map(Episode::startedAt).min(Comparator.naturalOrder()).orElseThrow()
                .minus(Scoring.LEAD);
        try {
            Scoring.Report report = Scoring.score(episodes, opsAgent.alertsSince(from), opsAgent.incidentsSince(from),
                    Instant.now());
            result.put("from", from);
            result.put("summary", report.summary());
            result.put("episodes", report.episodes().reversed());
            result.put("false_alarms", report.falseAlarms());
        } catch (RestClientException e) {
            result.put("message", "Ops-Agent (core-service) cannot be reached: " + e.getMessage());
        }
        return result;
    }
}
