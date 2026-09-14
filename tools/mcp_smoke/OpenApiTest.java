import com.hyfuse.bridge.mcp.McpHttpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.util.*;

/**
 * OpenApiTest — OpenAPI tool-server surface smoke (OpenWebUI integration).
 * GET /mcp/openapi.json must serve a spec with one POST operation per
 * registry tool at /tools/{name}; POST /mcp/tools/{name} must route tool
 * calls; bearer auth must gate both; the JSON-RPC /mcp surface must be
 * unchanged. Mirrors open-webui utils/tools.py: spec fetch -> operationId
 * discovery -> POST {base}/tools/{name} with arguments as the JSON body.
 */
public class OpenApiTest {
    static HttpClient http = HttpClient.newHttpClient();
    static final String AUTH = "Authorization";
    static final String KEY = "Bearer testtoken";

    static HttpResponse<String> send(String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:25599" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header(AUTH, auth);
        if (body != null) {
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static int failures = 0;

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + label + (ok ? "" : " — " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        try (McpHttpServer s = new McpHttpServer(java.net.InetAddress.getLoopbackAddress(), 25599, null, "testtoken")) {
            s.start();

            // 1. auth gate: spec without bearer -> 401
            HttpResponse<String> r = send("GET", "/mcp/openapi.json", null, null);
            check("spec GET without bearer -> 401", r.statusCode() == 401, "got " + r.statusCode());

            // 2. spec with bearer -> 200 JSON with paths
            r = send("GET", "/mcp/openapi.json", null, KEY);
            check("spec GET with bearer -> 200", r.statusCode() == 200, "got " + r.statusCode());
            JsonObject spec = JsonParser.parseString(r.body()).getAsJsonObject();
            check("spec is OpenAPI 3.1.0", "3.1.0".equals(spec.has("openapi")
                    ? spec.get("openapi").getAsString() : null), spec.toString());
            JsonObject info = spec.getAsJsonObject("info");
            check("spec info title HyFuse + version", "HyFuse".equals(info.has("title")
                    ? info.get("title").getAsString() : null) && info.has("version"), info.toString());

            // 3. every registry tool appears exactly once as a POST operation
            JsonObject paths = spec.getAsJsonObject("paths");
            Map<String, JsonObject> ops = new LinkedHashMap<>();
            Set<String> badShape = new LinkedHashSet<>();
            for (var e : paths.entrySet()) {
                JsonObject op = e.getValue().getAsJsonObject().get("post").getAsJsonObject();
                String id = op.has("operationId") ? op.get("operationId").getAsString() : null;
                if (id == null) { badShape.add(e.getKey() + ":no-op"); continue; }
                ops.put(id, op);
                if (!("/tools/" + id).equals(e.getKey())) badShape.add("path!=/tools/" + id + ":" + e.getKey());
                JsonObject schema = op.getAsJsonObject("requestBody").getAsJsonObject("content")
                        .getAsJsonObject("application/json").getAsJsonObject("schema");
                if (!"object".equals(schema.has("type") ? schema.get("type").getAsString() : null)
                        || !schema.has("properties")) badShape.add(id + ":schema");
                if (!op.has("description")) badShape.add(id + ":no-description");
            }
            check("spec paths == registry tool count (83)", ops.size() == 83, "got " + ops.size());
            check("all spec operations well-formed (/tools/{id}, object schema, description)",
                    badShape.isEmpty(), badShape.toString());

            // 4. spec parity with MCP tools/list (same registry, same names)
            Set<String> mcpNames = new LinkedHashSet<>();
            JsonObject list = JsonParser.parseString(postRpc(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).getAsJsonObject();
            for (var el : list.getAsJsonObject("result").getAsJsonArray("tools")) {
                mcpNames.add(el.getAsJsonObject().get("name").getAsString());
            }
            Set<String> specOnly = new TreeSet<>(ops.keySet()); specOnly.removeAll(mcpNames);
            Set<String> mcpOnly = new TreeSet<>(mcpNames); mcpOnly.removeAll(ops.keySet());
            check("spec operationIds == MCP tools/list names",
                    specOnly.isEmpty() && mcpOnly.isEmpty(), "specOnly:" + specOnly + " mcpOnly:" + mcpOnly);

            // 5. spot-check one schema carried over: place-block enum faceDirection
            JsonObject pb = ops.get("place-block").getAsJsonObject("requestBody").getAsJsonObject("content")
                    .getAsJsonObject("application/json").getAsJsonObject("schema");
            check("place-block schema carries faceDirection enum",
                    pb.getAsJsonObject("properties").getAsJsonObject("faceDirection").has("enum"),
                    pb.toString());

            // 6. tool-call routing: unknown tool -> 404 {"error":...}
            r = send("POST", "/mcp/tools/no-such-tool", "{}", KEY);
            check("unknown tool -> 404 with error body", r.statusCode() == 404
                    && JsonParser.parseString(r.body()).getAsJsonObject().has("error"),
                    r.statusCode() + " " + r.body());

            // 7. invalid JSON body -> 400
            r = send("POST", "/mcp/tools/get-agent-snapshot", "{not json", KEY);
            check("invalid JSON -> 400", r.statusCode() == 400, "got " + r.statusCode());

            // 8. tool-call auth gate: no bearer -> 401
            r = send("POST", "/mcp/tools/get-agent-snapshot", "{}", null);
            check("tool POST without bearer -> 401", r.statusCode() == 401, "got " + r.statusCode());

            // 9. known tool reaches dispatch: with a null dispatcher the call
            // fails in dispatch -> outer handler 500 (routing proof, not a
            // dispatch-behavior claim; live dispatch is operator-side)
            r = send("POST", "/mcp/tools/get-agent-snapshot", "{}", KEY);
            check("known tool POST routed to dispatch (500 w/ null dispatcher)",
                    r.statusCode() == 500, "got " + r.statusCode());

            // 10. JSON-RPC surface unchanged: initialize still answers on /mcp
            JsonObject init = JsonParser.parseString(postRpc(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}")).getAsJsonObject();
            JsonObject si = init.getAsJsonObject("result").getAsJsonObject("serverInfo");
            check("JSON-RPC initialize unchanged", si.has("name") && si.has("version"), init.toString());

            // 11. other GET paths still 405 (old behavior preserved)
            r = send("GET", "/mcp", null, KEY);
            check("GET /mcp still 405", r.statusCode() == 405, "got " + r.statusCode());
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECKS FAILED");
        if (failures > 0) System.exit(1);
    }

    static String postRpc(String body) throws Exception {
        return send("POST", "/mcp", body, KEY).body();
    }
}
