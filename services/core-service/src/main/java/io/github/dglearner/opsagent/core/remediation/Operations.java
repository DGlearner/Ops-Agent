package io.github.dglearner.opsagent.core.remediation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catalog of what may be done to the lab (docs/agent-design.md §5.1, §13.2): for each operation its parameters,
 * what must still hold right before it runs, how it runs, what is read back, and how recovery is then judged. Nothing
 * outside it can be run.
 */
final class Operations {

    static final Set<String> SWITCHES = Set.of("payment.fast-fail", "inventory.fast-fail");
    static final long MIN_TRANSACTION_AGE_S = 10;
    // The services that call each container, for its verification (the lab's topology, docs/shop-design.md §3).
    private static final Map<String, List<String>> DEPENDENTS = Map.of(
            "shop-mysql", List.of("order-service", "inventory-service", "payment-service"),
            "shop-redis", List.of("order-service"));

    record Check(boolean holds, String detail, Map<String, Object> observed) {
    }

    record Outcome(String status, String summary, Map<String, Object> observed) {
    }

    private Operations() {
    }

    /** The parameters as they will be used; IllegalArgumentException with the reason if they are not acceptable. */
    static Map<String, Object> params(String op, Map<String, Object> given, Set<String> containers) {
        Map<String, Object> p = given == null ? Map.of() : given;
        Map<String, Object> out = new LinkedHashMap<>();
        switch (op == null ? "" : op) {
            case "start_container" -> {
                only(p, Set.of("name"));
                if (!(p.get("name") instanceof String name) || !containers.contains(name)) {
                    throw new IllegalArgumentException("name must be one of " + containers);
                }
                out.put("name", name);
            }
            case "kill_transaction" -> {
                only(p, Set.of("trx_id", "thread_id"));
                if (!(p.get("trx_id") instanceof String trx) || !trx.matches("\\d{1,20}")) {
                    throw new IllegalArgumentException("trx_id must be the transaction's number, as a string");
                }
                if (!(p.get("thread_id") instanceof Number thread) || thread.longValue() <= 0
                        || thread.doubleValue() != thread.longValue()) {
                    throw new IllegalArgumentException("thread_id must be a positive integer");
                }
                out.put("trx_id", trx);
                out.put("thread_id", thread.longValue());
            }
            case "set_switch" -> {
                only(p, Set.of("name", "on", "minutes"));
                if (!(p.get("name") instanceof String name) || !SWITCHES.contains(name)) {
                    throw new IllegalArgumentException("name must be one of " + SWITCHES);
                }
                if (!(p.get("on") instanceof Boolean on)) {
                    throw new IllegalArgumentException("on must be true or false");
                }
                Object minutes = p.getOrDefault("minutes", 10);
                if (!(minutes instanceof Number m) || m.intValue() < 1 || m.intValue() > 60
                        || m.doubleValue() != m.intValue()) {
                    throw new IllegalArgumentException("minutes must be 1 to 60");
                }
                out.put("name", name);
                out.put("on", on);
                out.put("minutes", m.intValue());
            }
            default -> throw new IllegalArgumentException("No operation " + op
                    + "; the operations are start_container, kill_transaction, set_switch");
        }
        return out;
    }

    /** Checked again right before running: the card was decided on what the lab looked like then. */
    static Check precheck(String op, Map<String, Object> p, LabOperator lab) {
        return switch (op) {
            case "start_container" -> {
                String state = lab.containerState((String) p.get("name"));
                Map<String, Object> seen = Map.of("container", p.get("name"), "state", state);
                if (state.equals("missing")) {
                    yield new Check(false, "容器 " + p.get("name") + " 不存在", seen);
                }
                yield state.equals("running") ? new Check(false, p.get("name") + " 已经在运行，不需要启动", seen)
                        : new Check(true, p.get("name") + " 当前是 " + state, seen);
            }
            case "kill_transaction" -> {
                String trx = (String) p.get("trx_id");
                long thread = ((Number) p.get("thread_id")).longValue();
                LabOperator.Transaction found = lab.transactions(0).stream().filter(t -> t.trxId().equals(trx))
                        .findFirst().orElse(null);
                if (found == null) {
                    yield new Check(false, "事务 " + trx + " 已经结束", Map.of("trx_id", trx, "present", false));
                }
                Map<String, Object> seen = new LinkedHashMap<>(Map.of("trx_id", trx, "thread_id", found.threadId(),
                        "age_s", found.ageSeconds(), "state", found.state()));
                seen.put("blocking", found.blocking());
                if (found.threadId() != thread) {
                    yield new Check(false, "事务 " + trx + " 的线程是 " + found.threadId() + "，不是卡片上的 " + thread,
                            seen);
                }
                if (found.ageSeconds() < MIN_TRANSACTION_AGE_S) {
                    yield new Check(false, "事务 " + trx + " 只持续了 " + found.ageSeconds() + " 秒", seen);
                }
                if (found.waitingForLock()) {
                    // It is the one being held up: ending it frees nothing the others wait on.
                    yield new Check(false, "事务 " + trx + " 自己在等锁，不是持有锁的那个", seen);
                }
                yield new Check(true, "事务 " + trx + " 已持续 " + found.ageSeconds() + " 秒，"
                        + (found.blocking() == null ? "阻塞数未知" : "阻塞 " + found.blocking() + " 个"), seen);
            }
            case "set_switch" -> {
                String name = (String) p.get("name");
                LabOperator.Switch current = lab.switches().stream().filter(s -> s.name().equals(name)).findFirst()
                        .orElse(null);
                if (current == null) {
                    yield new Check(false, "order-service 没有开关 " + name, Map.of("switch", name));
                }
                Map<String, Object> seen = Map.of("switch", name, "on", current.on());
                yield current.on() == (Boolean) p.get("on")
                        ? new Check(false, name + " 已经是" + (current.on() ? "开" : "关") + "的", seen)
                        : new Check(true, name + " 当前是" + (current.on() ? "开" : "关") + "的", seen);
            }
            default -> throw new IllegalStateException(op);
        };
    }

    static void execute(String op, Map<String, Object> p, LabOperator lab) {
        switch (op) {
            case "start_container" -> lab.startContainer((String) p.get("name"));
            case "kill_transaction" -> lab.killThread(((Number) p.get("thread_id")).longValue());
            case "set_switch" -> lab.setSwitch((String) p.get("name"), (Boolean) p.get("on"),
                    ((Number) p.get("minutes")).intValue());
            default -> throw new IllegalStateException(op);
        }
    }

    /** What the lab looks like afterwards decides the receipt, not what the call answered. */
    static Outcome readBack(String op, Map<String, Object> p, LabOperator lab) {
        return switch (op) {
            case "start_container" -> {
                String state = lab.containerState((String) p.get("name"));
                Map<String, Object> seen = Map.of("container", p.get("name"), "state", state);
                yield state.equals("running")
                        ? new Outcome("executed", "已启动 " + p.get("name") + "，读回状态 running", seen)
                        : new Outcome("failed", "启动后读回 " + p.get("name") + " 的状态是 " + state, seen);
            }
            case "kill_transaction" -> {
                String trx = (String) p.get("trx_id");
                boolean present = lab.transactions(0).stream().anyMatch(t -> t.trxId().equals(trx));
                Map<String, Object> seen = Map.of("trx_id", trx, "present", present);
                yield present ? new Outcome("failed", "结束后读回事务 " + trx + " 仍然存在", seen)
                        : new Outcome("executed", "已结束事务 " + trx + "（线程 " + p.get("thread_id") + "），读回已不存在",
                        seen);
            }
            case "set_switch" -> {
                String name = (String) p.get("name");
                boolean on = lab.switches().stream().anyMatch(s -> s.name().equals(name) && s.on());
                Map<String, Object> seen = Map.of("switch", name, "on", on);
                yield on == (Boolean) p.get("on")
                        ? new Outcome("executed", "已把 " + name + (on ? " 打开" : " 关闭") + "，读回一致", seen)
                        : new Outcome("failed", "读回 " + name + " 是" + (on ? "开" : "关") + "的，和要求的不一致", seen);
            }
            default -> throw new IllegalStateException(op);
        };
    }

    /**
     * How the verification judges it: goal recover (the fault is gone) or contain (the bleeding stopped, a switch
     * on: orders are cancelled at once rather than recovered). Each check is decided from alerts and log counts.
     */
    static Map<String, Object> criteria(String op, Map<String, Object> p) {
        List<Map<String, Object>> checks;
        String goal = "recover";
        switch (op) {
            case "start_container" -> {
                List<String> dependents = DEPENDENTS.getOrDefault((String) p.get("name"), List.of("order-service"));
                checks = List.of(
                        Map.of("id", "no_new_alerts", "kind", "no_alerts", "services", dependents,
                                "title", "依赖它的服务没有新的告警"),
                        Map.of("id", "orders_paid", "kind", "events_at_least", "service", "order-service",
                                "event_type", "order_paid", "min", 1, "title", "订单服务有成功支付的订单"));
            }
            case "kill_transaction" -> checks = List.of(
                    Map.of("id", "no_lock_waits", "kind", "logs_at_most", "service", "inventory-service",
                            "text", "Lock wait timeout", "max", 0, "title", "库存服务没有新的锁等待超时"),
                    Map.of("id", "orders_paid", "kind", "events_at_least", "service", "order-service",
                            "event_type", "order_paid", "min", 1, "title", "订单服务有成功支付的订单"));
            case "set_switch" -> {
                boolean payment = p.get("name").equals("payment.fast-fail");
                String dependency = payment ? "payment-service" : "inventory-service";
                // The words of order-service's failure lines for the call the switch skips (OrderService.place).
                String words = payment ? "Charge" : "Inventory reservation failed";
                goal = Boolean.TRUE.equals(p.get("on")) ? "contain" : "recover";
                checks = List.of(
                        Map.of("id", "no_call_failures", "kind", "logs_at_most", "service", "order-service",
                                "event_type", "dependency_call_failed", "text", words, "max", 0,
                                "title", "订单服务不再有对 " + dependency + " 的调用失败"),
                        Boolean.TRUE.equals(p.get("on"))
                                ? Map.of("id", "degraded", "kind", "events_at_least", "service", "order-service",
                                        "event_type", "order_degraded", "min", 1, "title", "降级在生效（订单直接取消）")
                                : Map.of("id", "orders_paid", "kind", "events_at_least", "service", "order-service",
                                        "event_type", "order_paid", "min", 1, "title", "订单服务有成功支付的订单"));
            }
            default -> throw new IllegalStateException(op);
        }
        return Map.of("goal", goal, "checks", checks);
    }

    private static void only(Map<String, Object> p, Set<String> allowed) {
        for (String key : p.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown parameter " + key + "; allowed: " + allowed);
            }
        }
    }
}
