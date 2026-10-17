package io.github.dglearner.shop.loadgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fault scenarios (docs/shop-design.md §10). Each injects one fault into one service for the fault phase of a run and
 * clears it afterwards. {@code expected} says what the shop should do; it is the ground truth written to the report.
 */
public final class Scenarios {

    /** One step: an HTTP call to a service's management port, or a docker command. */
    public sealed interface Action {
        record Http(String service, String method, String path, String body) implements Action {
        }

        record Docker(String... args) implements Action {
        }
    }

    public record Scenario(String name, String targetService, String fault, String expected, List<Action> inject,
            List<Action> clear) {
    }

    private static final Map<String, Scenario> ALL = new LinkedHashMap<>();

    static {
        add(new Scenario("payment-timeout", "payment-service",
                "charges are processed but every answer comes 4 s late (order service gives up after 3 s)",
                "orders answer 202 PAYMENT_UNKNOWN, then reconciliation finds the charge and they end PAID",
                faults("payment", "{\"path\":\"/api/v1/payments\",\"method\":\"POST\",\"phase\":\"after\","
                        + "\"delay\":\"4s\"}"), clearFaults("payment")));
        add(new Scenario("payment-late", "payment-service",
                "charge requests are held 15 s before processing, longer than timeout plus reconciliation",
                "reconciliation voids the payment first, the late charge is refused, orders end CANCELLED "
                        + "with stock released and no money taken",
                faults("payment", "{\"path\":\"/api/v1/payments\",\"method\":\"POST\",\"delay\":\"15s\"}"),
                clearFaults("payment")));
        add(new Scenario("payment-errors", "payment-service",
                "30% of charge requests fail with 503 before processing",
                "those orders answer 202, reconciliation voids the never-made charge, they end CANCELLED",
                faults("payment", "{\"path\":\"/api/v1/payments\",\"method\":\"POST\",\"errors\":0.3,"
                        + "\"status\":503}"), clearFaults("payment")));
        add(new Scenario("payment-slow", "payment-service",
                "charges take 2.5 s, just under the order service's timeout",
                "orders still succeed but slowly; order-service request threads pile up (exhausted with a "
                        + "smaller server.tomcat.threads.max or a higher rate)",
                faults("payment", "{\"path\":\"/api/v1/payments\",\"method\":\"POST\",\"delay\":\"2500ms\"}"),
                clearFaults("payment")));
        add(new Scenario("inventory-slow", "inventory-service",
                "reservation requests are held 3 s before processing (order service gives up after 2 s)",
                "orders answer 503 INVENTORY_UNAVAILABLE; the release overtakes the reservation, which is then "
                        + "refused, so no stock leaks",
                faults("inventory", "{\"path\":\"/api/v1/reservations\",\"method\":\"POST\",\"delay\":\"3s\"}"),
                clearFaults("inventory")));
        add(new Scenario("inventory-errors", "inventory-service",
                "half of all reservation, confirmation and release requests fail with 503",
                "affected orders answer 503 and are cancelled; failed confirmations and releases are retried "
                        + "until the fault ends",
                faults("inventory", "{\"path\":\"/api/v1/reservations/**\",\"errors\":0.5,\"status\":503}"),
                clearFaults("inventory")));
        add(new Scenario("hot-row-lock", "inventory-service",
                "the stock row of SKU-1001 is locked for the whole fault phase, like a stuck long transaction",
                "reservations including SKU-1001 wait on the lock (MySQL lock wait timeout after 5 s), the "
                        + "order service times out after 2 s and cancels them; other SKUs keep selling",
                List.of(new Action.Http("inventory", "POST", "/actuator/rowlock",
                        "{\"sku\":\"SKU-1001\",\"duration\":\"%FAULT%\"}")),
                List.of(new Action.Http("inventory", "DELETE", "/actuator/rowlock", null))));
        // Two faults with no cause in common, at once: the counterexample for correlation (docs/shop-design.md §12.5).
        add(new Scenario("independent-faults", "payment-service, inventory-service",
                "30% of charge requests and, independently, 50% of reservation requests fail with 503",
                "two separate problems: Ops-Agent should keep them in two incidents, although both services use "
                        + "the same MySQL",
                concat(faults("payment", "{\"path\":\"/api/v1/payments\",\"method\":\"POST\",\"errors\":0.3,"
                                + "\"status\":503}"),
                        faults("inventory", "{\"path\":\"/api/v1/reservations\",\"method\":\"POST\","
                                + "\"errors\":0.5,\"status\":503}")),
                concat(clearFaults("payment"), clearFaults("inventory"))));
        add(new Scenario("mysql-down", "mysql",
                "the shop's MySQL container is stopped for the fault phase",
                "every service fails its database calls (503, connection errors in all logs); after MySQL is "
                        + "back, recovery closes orders interrupted mid-flight",
                List.of(new Action.Docker("stop", "%MYSQL%")),
                List.of(new Action.Docker("start", "%MYSQL%"))));
    }

    public static Scenario get(String name) {
        Scenario scenario = ALL.get(name);
        if (scenario == null) {
            throw new IllegalArgumentException("Unknown scenario " + name + "; one of " + String.join(", ", ALL.keySet()));
        }
        return scenario;
    }

    public static List<String> names() {
        return List.copyOf(ALL.keySet());
    }

    private static void add(Scenario scenario) {
        ALL.put(scenario.name(), scenario);
    }

    /** Injects one fault rule; it expires on its own shortly after the fault phase in case it is never cleared. */
    private static List<Action> faults(String service, String rule) {
        String expiring = rule.substring(0, rule.lastIndexOf('}')) + ",\"duration\":\"%EXPIRY%\"}";
        return List.of(new Action.Http(service, "POST", "/actuator/faults", expiring));
    }

    private static List<Action> concat(List<Action> first, List<Action> second) {
        List<Action> both = new ArrayList<>(first);
        both.addAll(second);
        return List.copyOf(both);
    }

    private static List<Action> clearFaults(String service) {
        return List.of(new Action.Http(service, "DELETE", "/actuator/faults", null));
    }

    private Scenarios() {
    }
}
