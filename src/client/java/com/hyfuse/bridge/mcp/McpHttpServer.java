package com.hyfuse.bridge.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.hyfuse.bridge.HyFuseClient;
import com.hyfuse.bridge.dispatch.ToolDispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal MCP server (Streamable HTTP subset) hosted inside the Fabric mod.
 *
 * Implements the JSON-RPC 2.0 surface an MCP client needs: initialize,
 * notifications/initialized, tools/list, tools/call, ping. Responses are plain
 * application/json (no SSE stream) — the Streamable HTTP spec permits this
 * for servers that do not stream, which covers tool calls here: every dispatch
 * completes with one result object.
 *
 * Transport: JDK built-in HttpServer. No new mod dependencies.
 *
 * Threading: requests are handled on a dedicated pool, never the Minecraft
 * client thread. {@link ToolDispatcher#dispatch} already marshals game access
 * through callOnClient(), so this handler thread is in the same position as
 * the former legacy WebSocket handler thread.
 *
 * Auth: optional bearer token checked in constant time on the Authorization
 * header: blank token means loopback-only unauthenticated (the same
 * policy the legacy WebSocket ToolServer used before its Removal).
 */
public final class McpHttpServer implements AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final String PROTOCOL_VERSION = "2025-06-18";
    /** Session id advertised on initialize; one per server lifetime. */
    private final String sessionId = UUID.randomUUID().toString();

    private final HttpServer server;
    private final ToolDispatcher dispatcher;
    private final String token;
    private final ExecutorService executor =
            Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "hyfuse-mcp-http");
                thread.setDaemon(true);
                return thread;
            });

    public McpHttpServer(InetAddress address, int port, ToolDispatcher dispatcher, String token) {
        this.dispatcher = dispatcher;
        this.token = token == null ? "": token;
        try {
            this.server = HttpServer.create(new InetSocketAddress(address, port), 0);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to bind MCP HTTP server to " + address + ":" + port, error);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    /** Bind and begin serving. Non-blocking. */
    public void start() {
        server.start();
        HyFuseClient.LOGGER.info("HyFuse MCP HTTP server listening on http://{}:{}/mcp (authentication {})",
                server.getAddress().getAddress().getHostAddress(), server.getAddress().getPort(),
                token.isBlank() ? "disabled; loopback only": "enabled");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ── HTTP layer ────────────────────────────────────────────────────────

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String httpMethod = exchange.getRequestMethod();

            // OpenAPI tool-server surface for OpenWebUI. OpenWebUI's
            // OpenAPI integration fetches the spec with GET {base}/openapi.json
            // and calls tools with POST {base}/tools/{name} (base = the URL
            // the user configured). Both ride the same bearer auth as the
            // JSON-RPC surface; tool names are single path segments.
            if ("GET".equals(httpMethod) && "/mcp/openapi.json".equals(path)) {
                if (!authenticate(exchange)) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    respondJson(exchange, 401, errorBody("unauthorized", null));
                    return;
                }
                respondJson(exchange, 200, openApiSpec());
                return;
            }
            if ("POST".equals(httpMethod) && path.startsWith("/mcp/tools/")
                    && path.indexOf('/', "/mcp/tools/".length()) < 0) {
                if (!authenticate(exchange)) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    respondJson(exchange, 401, errorBody("unauthorized", null));
                    return;
                }
                handleOpenApiToolCall(exchange, path.substring("/mcp/tools/".length()));
                return;
            }

            if (!"POST".equals(httpMethod)) {
                respondJson(exchange, 405, errorBody("method not allowed", null));
                return;
            }
            if (!authenticate(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                respondJson(exchange, 401, errorBody("unauthorized", null));
                return;
            }
            if (!"/mcp".equals(path)) {
                respondJson(exchange, 404, errorBody("not found", null));
                return;
            }

            JsonObject request = readRequest(exchange);
            if (request == null) {
                respondJson(exchange, 400, errorBody("invalid JSON", null));
                return;
            }

            // Session echo: the Streamable HTTP transport assigns a session id
            // on initialize and clients echo it via Mcp-Session-Id. We accept
            // any/none for now; a strict session check can be added once a
            // client is observed to require one.
            JsonElement id = request.get("id");
            String method = stringMember(request, "method");

            if (method == null) {
                respondJson(exchange, 400, errorBody("missing method", jsonRpcId(id)));
                return;
            }

            switch (method) {
                case "initialize" -> {
                    // Assign and announce a session id — many MCP clients expect
                    // Mcp-Session-Id on the initialize response and echo it on
                    // subsequent requests. We accept any/absent value for now.
                    exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
                    respondJson(exchange, 200, initializeResult(jsonRpcId(id)));
                }
                case "notifications/initialized" -> respondNoContent(exchange);
                case "notifications/cancelled" -> respondNoContent(exchange);
                case "ping" -> respondJson(exchange, 200, resultBody(jsonRpcId(id), new JsonObject()));
                case "tools/list" -> respondJson(exchange, 200,
                        resultBody(jsonRpcId(id), McpToolRegistry.toolsListResult()));
                case "tools/call" -> {
                    JsonObject body = respondToolCall(exchange, request, jsonRpcId(id));
                    respondJson(exchange, 200, body);
                }
                default -> respondJson(exchange, 200,
                        errorRpcBody(jsonRpcId(id), -32601, "method not found: " + method));
            }
        } catch (Throwable error) {
            HyFuseClient.LOGGER.error("MCP HTTP handler error", error);
            try {
                respondJson(exchange, 500, errorBody("internal error", null));
            } catch (IOException ignored) {
                // client already gone
            }
        }
    }

    private JsonObject respondToolCall(HttpExchange exchange, JsonObject request, Object id) throws IOException {
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params"): new JsonObject();
        String toolName = stringMember(params, "name");
        McpToolRegistry.Tool tool = McpToolRegistry.get(toolName);
        if (tool == null) {
            return errorRpcBody(id, -32602, "unknown tool: " + toolName);
        }
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments"): new JsonObject();

        // Dispatch off-thread and wait here — this is the MCP handler pool
        // thread, not the client thread. Long queue waits (enqueue-tasks up to
        // ~55s once it migrates here) hold this connection open, which is the
        // documented Streamable HTTP behavior for blocking tool calls.
        JsonObject result;
        try {
            result = dispatcher.dispatch(tool.name(), arguments).join();
        } catch (CompletionException | CancellationException error) {
            Throwable cause = error instanceof CompletionException completion && completion.getCause() != null
                    ? completion.getCause() : error;
            // MCP tool-call errors are IN-CONTENT (isError:true), not JSON-RPC
            // protocol errors — the client should see the failure text.
            return resultBody(id, toolCallError(cause));
        }

        return resultBody(id, toolCallResult(result));
    }

    private static JsonObject toolCallResult(JsonObject dispatchResult) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", GSON.toJson(dispatchResult));
        JsonArray contentList = new JsonArray();
        contentList.add(content);
        JsonObject call = new JsonObject();
        call.add("content", contentList);
        call.addProperty("isError", false);
        return call;
    }

    private static JsonObject toolCallError(Throwable cause) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        content.addProperty("text", "Tool execution failed: " + message);
        JsonArray contentList = new JsonArray();
        contentList.add(content);
        JsonObject call = new JsonObject();
        call.add("content", contentList);
        call.addProperty("isError", true);
        return call;
    }

    // ── OpenAPI tool-server surface ───────────────────────────────

    /**
     * OpenAPI 3.1 document generated from the tool registry: one POST
     * operation per tool at /tools/{name} with the tool's JSON Schema as the
     * application/json requestBody. OpenWebUI derives the function name from
     * operationId and the parameter schema from requestBody, then POSTs the
     * arguments to {base}/tools/{name}. The spec's servers field is not used
     * by OpenWebUI — the user-configured URL is the base.
     */
    private static JsonObject openApiSpec() {
        JsonObject info = new JsonObject();
        info.addProperty("title", "HyFuse");
        info.addProperty("version", modVersion());
        info.addProperty("description", "HyFuse Minecraft agent tools "
                + "(OpenAPI surface of the integrated MCP server; one POST operation per tool).");
        JsonObject spec = new JsonObject();
        spec.addProperty("openapi", "3.1.0");
        spec.add("info", info);
        JsonObject paths = new JsonObject();
        for (McpToolRegistry.Tool tool : McpToolRegistry.tools()) {
            JsonObject schemaRef = new JsonObject();
            schemaRef.add("schema", tool.inputSchema());
            JsonObject json = new JsonObject();
            json.add("application/json", schemaRef);
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("required", true);
            requestBody.add("content", json);
            JsonObject operation = new JsonObject();
            operation.addProperty("operationId", tool.name());
            operation.addProperty("summary", tool.name());
            operation.addProperty("description", tool.description());
            operation.add("requestBody", requestBody);
            JsonObject post = new JsonObject();
            post.add("post", operation);
            paths.add("/tools/" + tool.name(), post);
        }
        spec.add("paths", paths);
        return spec;
    }

    /**
     * One OpenAPI tool call: POST /mcp/tools/{name} with the arguments as the
     * JSON body. Dispatched through the same ToolDispatcher rail as MCP
     * tools/call. Honest dispatch results (ok:false, CLIENT_NOT_READY,...)
     * return 200 with the result object — the caller reads the in-band error
     * fields, same philosophy as MCP in-content tool errors. Only
     * transport-level failures map to HTTP errors: unknown tool 404, invalid
     * JSON body 400, unexpected dispatch failure 500.
     */
    private void handleOpenApiToolCall(HttpExchange exchange, String toolName) throws IOException {
        McpToolRegistry.Tool tool = McpToolRegistry.get(toolName);
        if (tool == null) {
            respondJson(exchange, 404, plainError("unknown tool: " + toolName));
            return;
        }
        JsonObject arguments = readArguments(exchange);
        if (arguments == null) {
            respondJson(exchange, 400, plainError("invalid JSON body"));
            return;
        }
        try {
            JsonObject result = dispatcher.dispatch(tool.name(), arguments).join();
            respondJson(exchange, 200, result);
        } catch (CompletionException | CancellationException error) {
            Throwable cause = error instanceof CompletionException completion && completion.getCause() != null
                    ? completion.getCause() : error;
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            respondJson(exchange, 500, plainError("tool execution failed: " + message));
        }
    }

    /** Arguments object for an OpenAPI tool call; blank body = {}; null = unparseable. */
    private static JsonObject readArguments(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return new JsonObject();
            }
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException error) {
            return null;
        }
    }

    /** Plain {"error":...} body for OpenAPI-surface HTTP errors. */
    private static JsonObject plainError(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body;
    }

    // ── JSON-RPC bodies ───────────────────────────────────────────────────

    private static Object jsonRpcId(JsonElement id) {
        if (id == null || id.isJsonNull()) {
            return null;
        }
        return id.isJsonPrimitive() ? id.getAsString() : GSON.toJson(id);
    }

    /**
     * Read the mod version from fabric.mod.json (via Fabric
     * Loader metadata) instead of the hardcoded "1.0.4" so the MCP
     * initialize handshake self-reports the deployed mod version.
     */
    private static String modVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
.getModContainer("hyfuse")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
.orElse("1.0.4");
        } catch (Throwable t) {
            return "1.0.4"; // last-resort fallback (pre-loader environments)
        }
    }

    private static JsonObject initializeResult(Object id) {
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "hyfuse");
        serverInfo.addProperty("version", modVersion());
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        JsonObject protocol = new JsonObject();
        protocol.addProperty("protocolVersion", PROTOCOL_VERSION);
        protocol.add("serverInfo", serverInfo);
        protocol.add("capabilities", capabilities);
        return resultBody(id, protocol);
    }

    private static JsonObject resultBody(Object id, JsonObject result) {
        JsonObject response = new JsonObject();
        if (id != null) response.addProperty("id", id.toString());
        response.addProperty("jsonrpc", "2.0");
        response.add("result", result);
        return response;
    }

    private static JsonObject errorRpcBody(Object id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        if (id != null) response.addProperty("id", id.toString());
        response.addProperty("jsonrpc", "2.0");
        response.add("error", error);
        return response;
    }

    private static JsonObject errorBody(String message, Object id) {
        return errorRpcBody(id, -32000, message);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Value of a nonblank string member, or null. */
    private static String stringMember(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) {
            return null;
        }
        String value = object.get(name).getAsString();
        return value.isBlank() ? null : value;
    }

    /** Parsed JSON object body, or null when missing/invalid. */
    private static JsonObject readRequest(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException error) {
            return null;
        }
    }

    private boolean authenticate(HttpExchange exchange) {
        if (token.isBlank()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String supplied = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()): "";
        return constantTimeEquals(token, supplied);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        int mismatch = expected.length() ^ supplied.length();
        int length = Math.max(expected.length(), supplied.length());
        for (int i = 0; i < length; i++) {
            char a = i < expected.length() ? expected.charAt(i) : 0;
            char b = i < supplied.length() ? supplied.charAt(i) : 0;
            mismatch |= a ^ b;
        }
        return mismatch == 0;
    }

    private static void respondJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] payload = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static void respondNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
    }

}
