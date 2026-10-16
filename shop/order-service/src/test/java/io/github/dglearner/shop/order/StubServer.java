package io.github.dglearner.shop.order;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A real HTTP server standing in for inventory or payment: scripted replies, optional delays, recorded requests. */
final class StubServer implements AutoCloseable {

    record Received(String method, String path, Map<String, String> headers, String body) {
    }

    record Reply(int status, String body, Duration delay) {

        static Reply json(int status, String body) {
            return new Reply(status, body, Duration.ZERO);
        }

        Reply after(Duration delay) {
            return new Reply(status, body, delay);
        }
    }

    private record Route(String method, Pattern path, Function<Received, Reply> handler) {
    }

    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private int port;
    private HttpServer server;

    StubServer() {
        start();
    }

    /** Starts listening, on the same port as before after a {@link #stop}. */
    synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
        port = server.getAddress().getPort();
    }

    /** Stops listening, so connections are refused. */
    synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    void reset() {
        routes.clear();
        received.clear();
        start();
    }

    /** Replies to {@code method} requests whose path matches {@code pathRegex}; later routes take precedence. */
    void on(String method, String pathRegex, Function<Received, Reply> handler) {
        routes.addFirst(new Route(method, Pattern.compile(pathRegex), handler));
    }

    List<Received> received(String method, String pathRegex) {
        Pattern pattern = Pattern.compile(pathRegex);
        return received.stream()
                .filter(r -> r.method().equals(method) && pattern.matcher(r.path()).matches())
                .toList();
    }

    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, String> headers = exchange.getRequestHeaders().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().toLowerCase(Locale.ROOT), e -> e.getValue().getFirst()));
            Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.add(request);
            Reply reply = new ArrayList<>(routes).stream()
                    .filter(r -> r.method().equals(request.method()) && r.path().matcher(request.path()).matches())
                    .findFirst()
                    .map(r -> r.handler().apply(request))
                    .orElse(Reply.json(500, "{\"code\":\"NO_STUB\"}"));
            if (!reply.delay().isZero()) {
                Thread.sleep(reply.delay());
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client gave up (read timeout) before the delayed reply was written.
        }
    }
}
