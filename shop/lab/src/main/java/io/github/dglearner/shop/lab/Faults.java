package io.github.dglearner.shop.lab;

import io.github.dglearner.shop.loadgen.FaultActions;
import io.github.dglearner.shop.loadgen.Scenarios;
import io.github.dglearner.shop.loadgen.ShopClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Faults switched on and off by hand, from the generator's scenarios (docs/shop-design.md §10.2). Every injection
 * has a planned duration and is cleared when it ends, if not before; the fault rules themselves also expire on the
 * services, so nothing is left behind if the lab stops. Each injection is an episode of the ground truth.
 */
@Component
class Faults implements AutoCloseable {

    private record Active(Episode episode, Scenarios.Scenario scenario, Instant endsAt, ScheduledFuture<?> autoClear) {
    }

    private final LabProperties properties;
    private final GroundTruth groundTruth;
    private final Traffic traffic;
    private final FaultActions actions;
    private final Map<String, Active> active = new LinkedHashMap<>();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("fault-clock").daemon().factory());

    Faults(LabProperties properties, GroundTruth groundTruth, Traffic traffic) {
        this.properties = properties;
        this.groundTruth = groundTruth;
        this.traffic = traffic;
        this.actions = new FaultActions(traffic.client(), properties.mysqlContainer());
    }

    /** Every scenario, with the episode it is running in if it is on. */
    synchronized List<Map<String, Object>> catalog() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : Scenarios.names()) {
            Scenarios.Scenario s = Scenarios.get(name);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("target", s.targetService());
            entry.put("fault", s.fault());
            entry.put("expected", s.expected());
            Active a = active.get(name);
            if (a != null) {
                entry.put("active_since", a.episode().startedAt());
                entry.put("ends_at", a.endsAt());
            }
            list.add(entry);
        }
        return list;
    }

    synchronized Episode inject(String name, Duration duration) {
        Scenarios.Scenario scenario = scenario(name);
        if (active.containsKey(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, name + " is already on");
        }
        if (!duration.isPositive() || duration.compareTo(properties.maxFaultDuration()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "duration must be positive and at most " + properties.maxFaultDuration().toMinutes() + " min");
        }
        Instant start = Instant.now();
        boolean ok = FaultActions.allOk(actions.perform("inject", scenario.inject(), duration));
        Episode episode = new Episode(UUID.randomUUID().toString(), "fault", name, scenario.targetService(),
                scenario.fault(), scenario.expected(), scenario.targetService().split(",").length, start, null, ok);
        groundTruth.record(episode);
        ScheduledFuture<?> autoClear = clock.schedule(() -> clear(name), duration.toMillis(), TimeUnit.MILLISECONDS);
        active.put(name, new Active(episode, scenario, start.plus(duration), autoClear));
        return episode;
    }

    synchronized Episode clear(String name) {
        Active a = active.remove(name);
        if (a == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, name + " is not on");
        }
        a.autoClear().cancel(false);
        boolean ok = FaultActions.allOk(actions.perform("clear", a.scenario().clear(), Duration.ZERO));
        Episode ended = a.episode().ended(Instant.now(), ok);
        groundTruth.record(ended);
        return ended;
    }

    /** Sets the SKU's stock, then sells it: an episode during which Ops-Agent should not alert. */
    Episode flashSale(String sku, int stock, double rate, Duration duration) {
        ShopClient.Response prepared = traffic.client().admin("inventory", "POST", "/actuator/stockprep",
                "{\"sku\":\"" + sku + "\",\"available\":" + stock + "}");
        if (prepared.status() != 200) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot prepare the stock of " + sku + ": HTTP "
                    + prepared.status() + " " + prepared.body().path("error").asString());
        }
        Instant start = Instant.now();
        Traffic.Run sale = traffic.flashSale(sku, rate, duration, 0.05);
        Episode episode = new Episode(UUID.randomUUID().toString(), "flash_sale", "flash-sale", sku,
                stock + " units of " + sku + " at " + rate + " orders/s for " + duration.toSeconds() + " s",
                "selling out is a normal result: no alert, no incident", 0, start, null, true);
        groundTruth.record(episode);
        clock.schedule(() -> groundTruth.record(episode.ended(sale.until, true)), duration.toMillis(),
                TimeUnit.MILLISECONDS);
        return episode;
    }

    private static Scenarios.Scenario scenario(String name) {
        try {
            return Scenarios.get(name);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** Clears whatever is still on when the lab stops. */
    @Override
    public synchronized void close() {
        new ArrayList<>(active.keySet()).forEach(this::clear);
        clock.shutdownNow();
    }
}
