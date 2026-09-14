import com.hyfuse.bridge.mcp.McpHttpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.util.*;

/** Full tools/list validation against the complete tool registry. */
public class Smoke2 {
    static HttpClient http = HttpClient.newHttpClient();

    static String post(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:25581/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    static int failures = 0;

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + label + (ok ? "" : " — " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        try (McpHttpServer s = new McpHttpServer(java.net.InetAddress.getLoopbackAddress(), 25581, null, "")) {
            s.start();

            // 1. initialize still good
            JsonObject init = JsonParser.parseString(post(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")).getAsJsonObject();
            JsonObject si = init.getAsJsonObject("result").getAsJsonObject("serverInfo");
            check("initialize nested serverInfo", si.has("name") && si.has("version"), init.toString());

            // 2. tools/list: 83 tools, every entry well-formed (79 + T4.4 use-item-on-block + T4.5 entity-interact + T4.6 bucket-fluid + T4.8 farm-plot)
            JsonObject list = JsonParser.parseString(post(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")).getAsJsonObject();
            JsonArray tools = list.getAsJsonObject("result").getAsJsonArray("tools");
            check("tools/list count == 83", tools.size() == 83, "got " + tools.size());

            Map<String, JsonObject> byName = new LinkedHashMap<>();
            Set<String> badShape = new LinkedHashSet<>();
            Set<String> badRequired = new LinkedHashSet<>();
            for (var el : tools) {
                JsonObject t = el.getAsJsonObject();
                String name = t.has("name") ? t.get("name").getAsString() : null;
                byName.put(name, t);
                if (!t.has("description") || !t.has("inputSchema")) badShape.add(name);
                JsonObject schema = t.getAsJsonObject("inputSchema");
                if (!"object".equals(schema.has("type") ? schema.get("type").getAsString() : null)
                        || !schema.has("properties")) badShape.add(name);
                // every required entry must exist in properties
                if (schema.has("required")) {
                    for (var r : schema.getAsJsonArray("required")) {
                        if (!schema.getAsJsonObject("properties").has(r.getAsString())) badRequired.add(name + ":" + r.getAsString());
                    }
                }
            }
            check("all tools have name+description+object inputSchema", badShape.isEmpty(), badShape.toString());
            check("all required fields present in properties", badRequired.isEmpty(), badRequired.toString());

            // Registry<->handler parity audit. Every advertised tool must
            // have a wired ToolDispatcher handler — an advertised but
            // UNKNOWN_TOOL defect fails the suite here instead of live.
            java.util.Set<String> advertised = byName.keySet();
            java.util.Set<String> handled = com.hyfuse.bridge.dispatch.ToolDispatcher.handlerNames();
            java.util.Set<String> unhandled = new java.util.TreeSet<>(advertised);
            unhandled.removeAll(handled);
            java.util.Set<String> hidden = new java.util.TreeSet<>(handled);
            hidden.removeAll(advertised);
            check("parity advertised == handled (" + handled.size() + " handlers)",
                    unhandled.isEmpty() && hidden.isEmpty(),
                    "unhandled: " + unhandled + " hidden: " + hidden);


            // 3. spot-check schema features
            JsonObject pb = byName.get("place-block").getAsJsonObject("inputSchema");
            check("place-block enum faceDirection",
                    pb.getAsJsonObject("properties").getAsJsonObject("faceDirection").has("enum")
                            && pb.getAsJsonArray("required").contains(new JsonParser().parse("\"x\"")),
                    "missing enum or required x");

            JsonObject et = byName.get("enqueue-tasks").getAsJsonObject("inputSchema");
            JsonObject taskItems = et.getAsJsonObject("properties").getAsJsonObject("tasks")
                    .getAsJsonObject("items");
            check("enqueue-tasks tasks.items has tool enum (11)",
                    taskItems.getAsJsonObject("properties").getAsJsonObject("tool")
                            .getAsJsonArray("enum").size() == 11, taskItems.toString());
            check("enqueue-tasks args is permissive object",
                    "object".equals(taskItems.getAsJsonObject("properties").getAsJsonObject("args")
                            .get("type").getAsString()), "args not object");

            JsonObject nv = byName.get("navigate-v2").getAsJsonObject("inputSchema");
            check("navigate-v2 goal is permissive object",
                    "object".equals(nv.getAsJsonObject("properties").getAsJsonObject("goal")
                            .get("type").getAsString()), "goal not plain object");

            JsonObject bs = byName.get("build-structure").getAsJsonObject("inputSchema");
            check("build-structure blueprint permissive + required",
                    "object".equals(bs.getAsJsonObject("properties").getAsJsonObject("blueprint")
                            .get("type").getAsString())
                            && bs.getAsJsonArray("required").contains(new JsonParser().parse("\"blueprint\"")),
                    "blueprint schema");

            JsonObject mb = byName.get("mine-blocks").getAsJsonObject("inputSchema");
            check("mine-blocks wait object with maxMs",
                    mb.getAsJsonObject("properties").getAsJsonObject("wait")
                            .getAsJsonObject("properties").has("maxMs"), "wait.maxMs missing");
            check("mine-blocks mode enum",
                    mb.getAsJsonObject("properties").getAsJsonObject("mode").has("enum"), "mode enum missing");

            JsonObject sm = byName.get("smelt-item").getAsJsonObject("inputSchema");
            check("smelt-item 5 required",
                    sm.getAsJsonArray("required").size() == 5, sm.getAsJsonArray("required").toString());

            JsonObject rs = byName.get("recover-stuck").getAsJsonObject("inputSchema");
            check("recover-stuck previousTarget nested xyz",
                    rs.getAsJsonObject("properties").getAsJsonObject("previousTarget")
                            .getAsJsonObject("properties").has("z"), "nested previousTarget missing z");

            JsonObject li = byName.get("list-inventory").getAsJsonObject("inputSchema");
            check("list-inventory empty-schema (properties present, required empty)",
                    li.has("properties") && li.getAsJsonArray("required").isEmpty(), li.toString());

            // 4. protocol error paths still good
            // get-current-action present with empty (properties-only) schema
            JsonObject gca = byName.get("get-current-action");
            check("get-current-action present, empty schema",
                    gca != null && gca.getAsJsonObject("inputSchema").has("properties")
                            && gca.getAsJsonObject("inputSchema").getAsJsonArray("required").isEmpty(),
                    gca == null ? "absent" : gca.toString());

            // craft-with-deps: item required, count/maxDepth optional integers
            JsonObject cwd = byName.get("craft-with-deps");
            check("craft-with-deps schema (item required + count/maxDepth optional)",
                    cwd != null
                            && cwd.getAsJsonObject("inputSchema").getAsJsonArray("required").contains(new JsonParser().parse("\"item\""))
                            && cwd.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("maxDepth"),
                    cwd == null ? "absent" : cwd.toString());

            String badTool = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{}}}");
            check("unknown tool -> -32602", badTool.contains("-32602"), badTool);
            String badJson = post("{not json");
            check("malformed json -> -32000", badJson.contains("-32000"), badJson);

            System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
            System.exit(failures == 0 ? 0 : 1);
        }
    }
}
