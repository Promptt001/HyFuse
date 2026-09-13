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
    public static void main(String[] a) throws Exception {
        // null dispatcher is fine: no tools/call in this smoke test
        try (McpHttpServer s = new McpHttpServer(java.net.InetAddress.getLoopbackAddress(), 25581, null, "")) {
            s.start();
            System.out.println("INIT:    " + post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
            System.out.println("PING:    " + post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"));
            System.out.println("BADJSON: " + post("{not json"));
            System.out.println("TOOLSLIST (first 240): " + post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}").substring(0, 240));
            System.out.println("BADTOOL: " + post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{}}}"));
        }
    }
}
