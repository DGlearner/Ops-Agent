package io.github.dglearner.opsagent.core;

import io.github.dglearner.opsagent.core.remediation.LabOperator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The shop lab in the tests: containers, transactions and switches in memory, and every call that changed one. */
class FakeLab implements LabOperator {

    final Map<String, String> containers = new ConcurrentHashMap<>(Map.of("shop-mysql", "exited", "shop-redis",
            "running"));
    final List<Transaction> transactions = new CopyOnWriteArrayList<>();
    final Map<String, Boolean> switches = new ConcurrentHashMap<>(Map.of("payment.fast-fail", false,
            "inventory.fast-fail", false));
    final List<String> changes = new CopyOnWriteArrayList<>();
    volatile boolean startDoesNothing;

    @Override
    public String containerState(String name) {
        return containers.getOrDefault(name, "missing");
    }

    @Override
    public void startContainer(String name) {
        changes.add("start " + name);
        if (!startDoesNothing) {
            containers.put(name, "running");
        }
    }

    @Override
    public List<Transaction> transactions(long minAgeSeconds) {
        return transactions.stream().filter(t -> t.ageSeconds() >= minAgeSeconds).toList();
    }

    @Override
    public void killThread(long threadId) {
        changes.add("kill " + threadId);
        transactions.removeIf(t -> t.threadId() == threadId);
    }

    @Override
    public List<Switch> switches() {
        List<Switch> all = new ArrayList<>();
        switches.forEach((name, on) -> all.add(new Switch(name, on, on ? Instant.now().plusSeconds(600) : null)));
        return all;
    }

    @Override
    public Switch setSwitch(String name, boolean on, int minutes) {
        changes.add("switch " + name + " " + on + " " + minutes);
        switches.put(name, on);
        return new Switch(name, on, null);
    }

    void reset() {
        containers.putAll(Map.of("shop-mysql", "exited", "shop-redis", "running"));
        transactions.clear();
        switches.replaceAll((k, v) -> false);
        changes.clear();
        startDoesNothing = false;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        FakeLab fakeLab() {
            return new FakeLab();
        }
    }
}
