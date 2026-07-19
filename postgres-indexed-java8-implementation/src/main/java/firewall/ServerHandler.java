package firewall;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Routes /health/ready, /v1/firewall/evaluate and /internal/run-batch. */
final class ServerHandler {
    private final EngineHolder holder;

    ServerHandler(EngineHolder holder) {
        this.holder = holder;
    }

    HttpHandler ready() {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try {
                    Engine engine = holder.get();
                    Map<String, Object> body = new LinkedHashMap<String, Object>();
                    body.put("status", "READY");
                    body.put("data_version", engine.dataVersion);
                    writeJson(exchange, 200, body);
                } finally {
                    exchange.close();
                }
            }
        };
    }

    HttpHandler evaluate() {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try {
                    if (!"POST".equals(exchange.getRequestMethod())) {
                        writeJson(exchange, 405, Json.error("method not allowed"));
                        return;
                    }
                    AccessLog access;
                    try {
                        access = (AccessLog) Json.parse(exchange.getRequestBody(), AccessLog.class);
                    } catch (IOException e) {
                        writeJson(exchange, 400, Json.error(e.getMessage()));
                        return;
                    } catch (RuntimeException e) {
                        writeJson(exchange, 400, Json.error(e.getMessage()));
                        return;
                    }
                    EvalResult result;
                    try {
                        result = holder.get().evaluate(access);
                    } catch (IllegalArgumentException e) {
                        writeJson(exchange, 400, Json.error(e.getMessage()));
                        return;
                    }
                    Map<String, Object> body = new LinkedHashMap<String, Object>();
                    body.put("access_id", result.accessId);
                    body.put("selected_policy_id", result.selectedPolicyId);
                    body.put("matched_rule_id", result.matchedRuleId);
                    body.put("action", result.action);
                    writeJson(exchange, 200, body);
                } finally {
                    exchange.close();
                }
            }
        };
    }

    HttpHandler runBatch(final BatchRunner runner) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try {
                    if (!"POST".equals(exchange.getRequestMethod())) {
                        writeJson(exchange, 405, Json.error("method not allowed"));
                        return;
                    }
                    BatchRequest request;
                    try {
                        request = (BatchRequest) Json.parse(exchange.getRequestBody(), BatchRequest.class);
                    } catch (IOException e) {
                        writeJson(exchange, 400, Json.error(e.getMessage()));
                        return;
                    }
                    if (request.input == null || request.input.isEmpty() || request.output == null || request.output.isEmpty()) {
                        writeJson(exchange, 400, Json.error("input and output are required"));
                        return;
                    }
                    try {
                        runner.run(request.input, request.output, request.workers);
                    } catch (Throwable t) {
                        writeJson(exchange, 500, Json.error(t.toString()));
                        return;
                    }
                    writeJson(exchange, 200, Json.status("complete"));
                } finally {
                    exchange.close();
                }
            }
        };
    }

    static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = Json.toBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        OutputStream output = exchange.getResponseBody();
        try {
            output.write(payload);
            output.flush();
        } finally {
            output.close();
        }
    }

    static final class BatchRequest {
        public String input;
        public String output;
        public Integer workers;
    }

    @FunctionalInterface
    interface BatchRunner {
        void run(String input, String output, Integer workers) throws Exception;
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
