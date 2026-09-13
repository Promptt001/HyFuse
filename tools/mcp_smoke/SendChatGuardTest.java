package com.hyfuse.bridge.dispatch;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import java.lang.reflect.Field;

/**
 * Regression test: send-chat pre-flight validation pins.
 * Live pin: an over-length message previously reached the vanilla encoder
 * and DISCONNECTED the client (kick). All rejections must fire as
 * CHAT_REJECTED ToolException BEFORE any client access — which is why a
 * null Minecraft argument is valid for the rejection paths.
 */
public class SendChatGuardTest {
    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + name + (ok ? "" : " — " + detail));
        if (!ok) fails++;
    }

    static String rejectCode(String message) throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("message", message);
        try {
            java.lang.reflect.Method m = ToolDispatcher.class.getDeclaredMethod("sendChat",
                    Minecraft.class, JsonObject.class);
            m.setAccessible(true);
            m.invoke(null, (Object) null, args);
            return "NO_THROW";
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable t = ite.getCause();
            if (t instanceof ToolDispatcher.ToolException te) return te.code();
            return "OTHER:" + t.getClass().getSimpleName();
        }
    }

    public static void main(String[] args) throws Exception {
        Field last = ToolDispatcher.class.getDeclaredField("lastChatSendMs");
        last.setAccessible(true);

        // 1. Empty message -> CHAT_REJECTED
        check("empty_rejected", "CHAT_REJECTED".equals(rejectCode("")),
                "expected CHAT_REJECTED");

        // Bare '/' (empty command) -> CHAT_REJECTED pre-wire.
        // '/'-prefixed messages are dispatched as command packets from now
        // on; a message that is ONLY the slash has no command to send.
        last.setLong(null, 0L); // fresh rate window — rejection must not need it
        check("bare_slash_rejected", "CHAT_REJECTED".equals(rejectCode("/")),
                "expected CHAT_REJECTED for bare '/'");
        check("bare_slash_ws_rejected", "CHAT_REJECTED".equals(rejectCode(" / ")),
                "expected CHAT_REJECTED for whitespace-around-slash");
        last.setLong(null, 0L);

        // 2. Over-length (201..256+ chars) -> CHAT_REJECTED, never the wire
        check("len201_rejected", "CHAT_REJECTED".equals(rejectCode("x".repeat(201))),
                "expected CHAT_REJECTED");
        check("len256_rejected", "CHAT_REJECTED".equals(rejectCode("x".repeat(256))),
                "expected CHAT_REJECTED");
        check("len500_rejected", "CHAT_REJECTED".equals(rejectCode("x".repeat(500))),
                "expected CHAT_REJECTED");

        // 3. Rate limit: fresh rate window -> CHAT_REJECTED
        last.setLong(null, System.currentTimeMillis());
        check("rate_rejected", "CHAT_REJECTED".equals(rejectCode("valid 20-char msg")),
                "expected CHAT_REJECTED (rate)");

        // 4. Validation passes for a legal message when the window is stale:
        //    with a null client the call must get PAST validation and die on
        //    client access instead (NPE) — proving the guards let it through.
        last.setLong(null, System.currentTimeMillis() - 10_000L);
        String code = rejectCode("valid 20-char msg");
        check("legal_passes_validation", "OTHER:NullPointerException".equals(code),
                "expected NPE past validation, got " + code);

        // 5. Constants pin the published contract
        Field maxLen = ToolDispatcher.class.getDeclaredField("CHAT_MAX_LENGTH");
        maxLen.setAccessible(true);
        Field rateMs = ToolDispatcher.class.getDeclaredField("CHAT_RATE_LIMIT_MS");
        rateMs.setAccessible(true);
        check("max_is_200", maxLen.getInt(null) == 200, "expected 200");
        check("rate_is_3000ms", rateMs.getLong(null) == 3000L, "expected 3000");

        System.out.println(fails == 0 ? "SendChatGuardTest: ALL PASS" : "SendChatGuardTest: " + fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
