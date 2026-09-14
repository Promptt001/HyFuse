import com.hyfuse.bridge.mcp.McpHttpServer;
import com.hyfuse.bridge.mcp.McpToolRegistry;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;

public class Smoke {
    static HttpClient http = HttpClient.newHttpClient();
    static String post(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:25581/mcp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
    static int failures = 0;
    /** Prints the raw exchange and asserts the body contains the expected marker. */
    static void check(String label, String body, String expect) {
        System.out.println(label + ": " + body);
        if (body == null || !body.contains(expect)) {
            System.out.println("FAIL " + label + " — expected to contain: " + expect);
            failures++;
        }
    }
    public static void main(String[] a) throws Exception {
        // null dispatcher is fine: no tools/call success path in this smoke test
        try (McpHttpServer s = new McpHttpServer(java.net.InetAddress.getLoopbackAddress(), 25581, null, "")) {
            s.start();
            check("INIT",    post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"),
                  "\"serverInfo\"");
            check("PING",    post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"),
                  "\"result\":{}");
            check("BADJSON", post("{not json"),
                  "-32000");
            check("TOOLSLIST (first 240)",
                  post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}").substring(0, 240),
                  "\"tools\":[");
            check("BADTOOL", post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{}}}"),
                  "-32602");
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECKS FAILED");
        if (failures > 0) System.exit(1);
    }
}
