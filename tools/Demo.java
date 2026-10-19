import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Starts the whole demo on one machine: the containers, Ops-Agent's services, the shop and its lab, the log shipper,
 * the agent, its chat server and the console. Ctrl+C stops the processes it started; the containers keep running.
 *
 * <pre>
 * java tools/Demo.java [--skip-build] [--baseline]    start everything, then wait for Ctrl+C
 * java tools/Demo.java stop                          stop processes an earlier run left behind, and the containers
 * </pre>
 *
 * Runs from the repository root on any OS with JDK 21 (single-file source launch, no bash). Also needs Docker
 * (running), Node.js 22.12+ and Python 3.11+. Every process writes to logs/demo/NAME.log.
 */
public class Demo {

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path LOGS = ROOT.resolve("logs/demo");
    /** NAME PID START_MILLIS of the launcher and each process it started, for "stop" from another terminal. */
    private static final Path PIDS = LOGS.resolve("processes.txt");
    private static final Path AGENT = ROOT.resolve("agent");
    private static final Path WEB = ROOT.resolve("web");
    private static final List<String> OPS = List.of("docker", "compose", "-f", "deploy/compose/compose.yaml");
    private static final List<String> SHOP = List.of("docker", "compose", "-f", "deploy/shop/compose.yaml");
    private static final int[] PORTS = {8080, 8081, 8082, 8083, 8090, 8091, 8092, 8093, 8191, 8192, 8193, 5180};
    private static final Duration STARTUP = Duration.ofMinutes(3);
    // Reads the agent's settings the way the agent does (environment, then agent/.env) and never prints the key.
    private static final String AGENT_MODE = "from ops_agent.config import Settings; s = Settings(); "
            + "k = s.llm_api_key.get_secret_value() if s.llm_api_key else ''; "
            + "ok = s.llm_base_url and s.llm_model and k; "
            + "print('baseline' if s.mode == 'baseline' else ('llm ' + s.llm_model if ok else 'unset'))";

    record Service(String name, Path dir, List<String> command, Map<String, String> env, String readyUrl) {}

    record Running(Service service, Process process) {}

    private final List<Running> started = new CopyOnWriteArrayList<>();
    // HTTP/1.1: by default the client asks to upgrade to HTTP/2, which Node's server (the console) leaves unanswered.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private volatile boolean stopping;

    public static void main(String[] args) throws Exception {
        boolean build = true;
        boolean baseline = false;
        boolean stop = false;
        for (String arg : args) {
            switch (arg) {
                case "--skip-build" -> build = false;
                case "--baseline" -> baseline = true;
                case "stop" -> stop = true;
                default -> {
                    System.err.println("usage: java tools/Demo.java [--skip-build] [--baseline]");
                    System.err.println("       java tools/Demo.java stop");
                    System.exit(2);
                }
            }
        }
        if (!Files.exists(ROOT.resolve("settings.gradle.kts")) || !Files.isDirectory(WEB)) {
            fail("run it from the repository root: java tools/Demo.java");
        }
        Files.createDirectories(LOGS);
        if (stop) {
            stopLeftovers();
            step("Stopping the containers (their data is kept)");
            exec(compose(OPS, "stop"), ROOT, "containers.log");
            exec(compose(SHOP, "stop"), ROOT, "containers.log");
            System.out.println("Stopped.");
            return;
        }
        new Demo().up(build, baseline);
    }

    private void up(boolean build, boolean baseline) throws Exception {
        step("Checking the tools");
        if (Runtime.version().feature() < 21) {
            fail("needs JDK 21 or newer, this is " + Runtime.version());
        }
        String node = checkNode();
        if (exec(List.of("docker", "info", "--format", "{{.ServerVersion}}"), ROOT, "containers.log", false) != 0) {
            fail("Docker is not running (on Windows: start Docker Desktop)");
        }
        if (!leftovers().isEmpty()) {
            fail("the demo is already running, or an earlier run left processes behind: java tools/Demo.java stop");
        }
        List<Integer> busy = busyPorts();
        if (!busy.isEmpty()) {
            fail("ports already in use: " + busy + " (another copy of the services running, e.g. from IntelliJ?)");
        }
        System.out.println("  JDK " + Runtime.version().feature() + ", Node.js " + node + ", Docker");

        step("Starting the containers: Kafka, PostgreSQL, Redis, and the shop's MySQL and Redis");
        exec(compose(OPS, "up", "-d", "--wait", "--wait-timeout", "180", "kafka", "postgres", "redis"), ROOT,
                "containers.log");
        exec(compose(SHOP, "up", "-d", "--wait", "--wait-timeout", "180", "mysql", "redis"), ROOT, "containers.log");
        exec(compose(OPS, "up", "-d", "kafka-init"), ROOT, "containers.log");
        awaitTopics();

        if (build) {
            step("Building the services (log: logs/demo/build.log)");
            // No daemon: the build's JVM exits afterwards and leaves its memory to the services.
            exec(gradle("--no-daemon", "--console=plain", "bootJar", ":tools:log-shipper:installDist"), ROOT,
                    "build.log");
        }
        // In two waves: seven JVMs starting at once on a small machine slow down the shop's first connections to
        // MySQL, which have a 2-second timeout.
        List<List<Service>> waves = List.of(opsAgentServices(), shopServices());
        for (Service s : waves.stream().flatMap(List::stream).toList()) {
            Path jar = ROOT.resolve(s.command().get(s.command().indexOf("-jar") + 1));
            if (!Files.exists(jar)) {
                fail(ROOT.relativize(jar) + " is missing; run without --skip-build");
            }
        }
        prepareWeb();
        Path python = prepareAgent();
        String mode = agentMode(python, baseline);

        step("Starting the services");
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopStarted));
        Files.deleteIfExists(PIDS);
        // The launcher too: "stop" from another terminal then ends it along with its processes.
        remember("launcher", ProcessHandle.current());
        for (List<Service> wave : waves) {
            List<Running> running = new ArrayList<>();
            for (Service s : wave) {
                running.add(start(s));
            }
            awaitReady(running);
        }
        // Every jar in lib/; a Windows path cannot hold the '*', so it is appended to the string.
        String shipperClasspath = Path.of("tools/log-shipper/build/install/log-shipper/lib") + File.separator + "*";
        Service shipper = new Service("log-shipper", ROOT,
                List.of(java(), "-Xmx128m", "-cp", shipperClasspath,
                        "io.github.dglearner.opsagent.shipper.LogShipper", "--dir", "logs/shop"), Map.of(), null);
        Map<String, String> pythonEnv = Map.of("PYTHONUNBUFFERED", "1", "PYTHONIOENCODING", "utf-8");
        Service agent = new Service("agent", AGENT,
                List.of(python.toString(), "-m", "ops_agent", "--mode", mode.split(" ")[0], "run"), pythonEnv, null);
        Service chat = new Service("chat", AGENT,
                List.of(python.toString(), "-m", "ops_agent", "--mode", mode.split(" ")[0], "chat"), pythonEnv,
                "http://127.0.0.1:8083/api/v1/chat/health");
        Service console = new Service("console", WEB, List.of("node", "node_modules/vite/bin/vite.js"), Map.of(),
                "http://127.0.0.1:5180/");
        List<Running> rest = List.of(start(shipper), start(agent), start(chat), start(console));
        awaitReady(rest);
        Thread.sleep(3000);
        for (Running r : rest) {
            if (!r.process().isAlive()) {
                fail(exited(r));
            }
        }

        System.out.printf("""

                Ops-Agent is running.
                  console    http://127.0.0.1:5180    incidents, the agent's analyses, the topology
                  assistant  http://127.0.0.1:5180/chat   ask about alerts and incidents
                  lab        http://127.0.0.1:8090    traffic and faults to try it on
                  core API   http://127.0.0.1:8080/swagger-ui.html
                  agent      %s
                  logs       logs/demo/
                In the lab, start the traffic and inject a fault; the incident shows up in the console within seconds,
                and the agent's analysis about a minute after its last alert.
                Ctrl+C stops these processes; the containers keep running (java tools/Demo.java stop stops them too).
                %n""", mode.startsWith("llm") ? "language model " + mode.substring(4)
                : "baseline: no language model, the incident's own correlation hints are the answer");
        watch();
    }

    // ---- what runs

    private static List<Service> opsAgentServices() {
        return List.of(
                jar("ingest-service", "services/ingest-service", 8081, "-Xmx256m"),
                jar("stream-processor", "services/stream-processor", 8082, "-Xmx384m"),
                // Faults in a demo come minutes apart. With the default 15-minute gap, the next fault's alerts would
                // join the previous incident whenever a dependency path connects the two (docs/project-design.md).
                jar("core-service", "services/core-service", 8080, "-Xmx384m", "--ops.incident.correlation-gap=3m"));
    }

    private static List<Service> shopServices() {
        String shopLogs = "-Dshop.log-dir=" + ROOT.resolve("logs/shop");
        return List.of(
                jar("inventory-service", "shop/inventory-service", 8192, "-Xmx256m", shopLogs),
                jar("payment-service", "shop/payment-service", 8193, "-Xmx256m", shopLogs),
                jar("order-service", "shop/order-service", 8191, "-Xmx256m", shopLogs),
                jar("lab", "shop/lab", 8090, "-Xmx192m"));
    }

    /** A Spring Boot jar; JVM options start with -X or -D, everything else goes to the application. */
    private static Service jar(String name, String project, int healthPort, String... options) {
        List<String> command = new ArrayList<>(List.of(java()));
        Stream.of(options).filter(o -> o.startsWith("-X") || o.startsWith("-D")).forEach(command::add);
        command.add("-jar");
        command.add(project + "/build/libs/" + name + ".jar");
        Stream.of(options).filter(o -> o.startsWith("--")).forEach(command::add);
        return new Service(name, ROOT, command, Map.of(), "http://127.0.0.1:" + healthPort + "/actuator/health");
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
    }

    private static List<String> gradle(String... args) {
        List<String> command = new ArrayList<>(WINDOWS ? List.of("cmd", "/c", "gradlew.bat") : List.of("./gradlew"));
        command.addAll(List.of(args));
        return command;
    }

    private static List<String> compose(List<String> file, String... args) {
        List<String> command = new ArrayList<>(file);
        command.addAll(List.of(args));
        return command;
    }

    // ---- preparing

    private static String checkNode() throws Exception {
        Optional<String> version = capture(List.of("node", "--version"), ROOT).map(String::strip);
        if (version.isEmpty()) {
            fail("needs Node.js 22.12 or newer for the console (node was not found)");
        }
        String[] parts = version.get().replaceFirst("^v", "").split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        if (!(major == 22 && minor >= 12 || major >= 24)) {
            fail("needs Node.js 22.12+ or 24+ for the console, this is " + version.get());
        }
        return version.get();
    }

    /** Installs the console's packages when node_modules is missing or older than the lock file. */
    private static void prepareWeb() throws Exception {
        Path installed = WEB.resolve("node_modules/.package-lock.json");
        if (Files.exists(installed) && !older(installed, WEB.resolve("package-lock.json"))) {
            return;
        }
        step("Installing the console's packages (npm ci, log: logs/demo/web-install.log)");
        exec(WINDOWS ? List.of("cmd", "/c", "npm", "ci") : List.of("npm", "ci"), WEB, "web-install.log");
    }

    /** Creates agent/.venv and installs the agent into it when missing or older than pyproject.toml. */
    private static Path prepareAgent() throws Exception {
        Path venv = AGENT.resolve(".venv");
        Path python = venv.resolve(WINDOWS ? "Scripts/python.exe" : "bin/python");
        if (!Files.exists(python)) {
            step("Creating the agent's virtual environment agent/.venv");
            List<String> command = new ArrayList<>(basePython());
            command.addAll(List.of("-m", "venv", ".venv"));
            exec(command, AGENT, "agent-install.log");
        }
        Path marker = venv.resolve(".demo-installed");
        if (!Files.exists(marker) || older(marker, AGENT.resolve("pyproject.toml"))) {
            step("Installing the agent (pip install -e agent, log: logs/demo/agent-install.log)");
            exec(List.of(python.toString(), "-m", "pip", "install", "-e", "."), AGENT, "agent-install.log");
            Files.writeString(marker, Instant.now() + "\n");
        }
        return python;
    }

    private static List<String> basePython() throws Exception {
        List<List<String>> candidates = WINDOWS ? List.of(List.of("py", "-3"), List.of("python"))
                : List.of(List.of("python3"), List.of("python"));
        for (List<String> candidate : candidates) {
            List<String> command = new ArrayList<>(candidate);
            command.addAll(List.of("-c", "import sys; print(sys.version_info >= (3, 11))"));
            if (capture(command, ROOT).map(String::strip).filter("True"::equals).isPresent()) {
                return candidate;
            }
        }
        return fail("needs Python 3.11 or newer for the agent");
    }

    private static String agentMode(Path python, boolean baseline) throws Exception {
        if (baseline) {
            return "baseline";
        }
        String mode = capture(List.of(python.toString(), "-c", AGENT_MODE), AGENT).map(String::strip)
                .orElseGet(() -> fail("cannot read the agent's settings; try: cd agent && .venv/bin/ops-agent --help"));
        if (mode.equals("unset")) {
            System.out.println("  No language model is configured (agent/.env or OPS_AGENT_LLM_*, see agent/README.md),"
                    + " so the agent runs in baseline mode.");
            return "baseline";
        }
        return mode;
    }

    /** kafka-init creates the topics; stream-processor.dlt is the last one (deploy/compose/kafka/create-topics.sh). */
    private static void awaitTopics() throws Exception {
        List<String> list = compose(OPS, "exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh",
                "--bootstrap-server", "localhost:9092", "--list");
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (Instant.now().isBefore(deadline)) {
            if (capture(list, ROOT).orElse("").lines().anyMatch("stream-processor.dlt"::equals)) {
                return;
            }
            Thread.sleep(3000);
        }
        fail("the Kafka topics were not created; see: docker compose -f deploy/compose/compose.yaml logs kafka-init");
    }

    // ---- running

    private Running start(Service s) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(s.command()).directory(s.dir().toFile()).redirectErrorStream(true)
                .redirectOutput(LOGS.resolve(s.name() + ".log").toFile());
        builder.environment().putAll(s.env());
        Process process = builder.start();
        Running running = new Running(s, process);
        started.add(running);
        remember(s.name(), process.toHandle());
        System.out.println("  started " + s.name());
        return running;
    }

    private static void remember(String name, ProcessHandle process) throws IOException {
        long since = process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        Files.writeString(PIDS, name + " " + process.pid() + " " + since + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void awaitReady(List<Running> processes) throws Exception {
        Instant begin = Instant.now();
        Set<Running> waiting = new HashSet<>();
        processes.stream().filter(r -> r.service().readyUrl() != null).forEach(waiting::add);
        while (!waiting.isEmpty()) {
            for (Running r : List.copyOf(waiting)) {
                if (!r.process().isAlive()) {
                    fail(exited(r));
                }
                if (ready(r.service().readyUrl())) {
                    waiting.remove(r);
                    System.out.printf("  ready   %-18s %3d s%n", r.service().name(),
                            Duration.between(begin, Instant.now()).toSeconds());
                }
            }
            if (Duration.between(begin, Instant.now()).compareTo(STARTUP) > 0) {
                fail("not ready after " + STARTUP.toMinutes() + " minutes: "
                        + waiting.stream().map(r -> r.service().name()).sorted().toList() + "; see logs/demo/");
            }
            Thread.sleep(1000);
        }
    }

    private boolean ready(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Reports processes that stop on their own until Ctrl+C. */
    private void watch() throws InterruptedException {
        Set<Running> reported = new HashSet<>();
        while (true) {
            for (Running r : started) {
                if (!stopping && !r.process().isAlive() && reported.add(r)) {
                    System.out.println(exited(r));
                }
            }
            Thread.sleep(2000);
        }
    }

    private static String exited(Running r) {
        String name = r.service().name();
        StringBuilder text = new StringBuilder(name + " exited with code " + r.process().exitValue()
                + "; logs/demo/" + name + ".log ends with:");
        tail(LOGS.resolve(name + ".log"), 25).forEach(line -> text.append("\n    ").append(line));
        return text.toString();
    }

    // ---- stopping

    private void stopStarted() {
        stopping = true;
        if (started.isEmpty()) {
            return;
        }
        System.out.println("\nStopping the processes...");
        terminate(started.stream().map(r -> r.process().toHandle()).toList());
        try {
            Files.deleteIfExists(PIDS);
        } catch (IOException ignored) {
            // "stop" skips entries whose process is gone
        }
        System.out.println("Stopped. The containers keep running; java tools/Demo.java stop stops them.");
    }

    private static void stopLeftovers() throws IOException {
        List<ProcessHandle> left = leftovers();
        if (!left.isEmpty()) {
            step("Stopping " + left.size() + " processes of a running or an earlier, killed launcher");
            terminate(left);
        }
        Files.deleteIfExists(PIDS);
    }

    /** Processes in the pid file that still run; the start time guards against a reused pid. */
    private static List<ProcessHandle> leftovers() throws IOException {
        if (!Files.exists(PIDS)) {
            return List.of();
        }
        List<ProcessHandle> alive = new ArrayList<>();
        for (String line : Files.readAllLines(PIDS)) {
            String[] fields = line.strip().split(" ");
            if (fields.length != 3) {
                continue;
            }
            long since = Long.parseLong(fields[2]);
            ProcessHandle.of(Long.parseLong(fields[1]))
                    .filter(h -> h.info().startInstant().map(i -> Math.abs(i.toEpochMilli() - since) < 2000)
                            .orElse(false))
                    .ifPresent(alive::add);
        }
        return alive;
    }

    /** Asks the processes and their children to stop, then kills what is left after 20 seconds. */
    private static void terminate(List<ProcessHandle> processes) {
        List<ProcessHandle> all = new ArrayList<>();
        for (ProcessHandle process : processes) {
            all.addAll(process.descendants().toList());
            all.add(process);
        }
        all.forEach(ProcessHandle::destroy);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        for (ProcessHandle h : all) {
            try {
                long left = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
                h.onExit().get(left, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                h.destroyForcibly();
            }
        }
    }

    // ---- helpers

    private static void step(String text) {
        System.out.println("==> " + text);
    }

    private static <T> T fail(String message) {
        System.err.println("error: " + message);
        System.exit(1);
        return null;
    }

    private static boolean older(Path file, Path than) throws IOException {
        return Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(than)) < 0;
    }

    /** Runs a command to its end, its output appended to logs/demo/LOG; on failure shows the end of it and exits. */
    private static void exec(List<String> command, Path dir, String log) throws Exception {
        exec(command, dir, log, true);
    }

    private static int exec(List<String> command, Path dir, String log, boolean required) throws Exception {
        Path file = LOGS.resolve(log);
        Files.writeString(file, "$ " + String.join(" ", command) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        int code;
        try {
            code = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(file.toFile())).start().waitFor();
        } catch (IOException e) {
            code = -1;
            Files.writeString(file, e.getMessage() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        if (code != 0 && required) {
            System.err.println("  failed: " + String.join(" ", command) + "; logs/demo/" + log + " ends with:");
            tail(file, 25).forEach(line -> System.err.println("    " + line));
            fail("stopped");
        }
        return code;
    }

    /** Output of a command that succeeded, or empty when it failed or could not start. */
    private static Optional<String> capture(List<String> command, Path dir) throws InterruptedException {
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static List<String> tail(Path file, int lines) {
        try {
            // Lenient: a tool may write in the console's code page rather than UTF-8.
            List<String> all = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList();
            return all.subList(Math.max(0, all.size() - lines), all.size());
        } catch (IOException e) {
            return List.of("(cannot read " + file + ")");
        }
    }

    private static List<Integer> busyPorts() {
        List<Integer> busy = new ArrayList<>();
        for (int port : PORTS) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                busy.add(port);
            } catch (IOException free) {
                // nothing listens there
            }
        }
        return busy;
    }
}
