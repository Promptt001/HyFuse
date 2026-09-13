import com.hyfuse.bridge.sense.DeathMemory;
import com.hyfuse.bridge.sense.EventBuffer;

import java.util.List;

/** Direct test of the sense classes (no Minecraft runtime needed). */
public class SenseTest {
    static int failures = 0;

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + label + (ok ? "" : " — " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        // ── EventBuffer ──
        EventBuffer eb = new EventBuffer();
        eb.add("entityHurt", "self", null, 15.0, null, null, null);
        eb.add("health", "self", null, 16.0, null, null, null);
        eb.add("entitySpawn", "zombie", null, null, true, null, null);
        eb.add("weatherUpdate", null, null, null, null, "rain", null);

        List<EventBuffer.Event> peeked = eb.peek();
        check("peek returns 4 without draining", peeked.size() == 4, "got " + peeked.size());

        List<EventBuffer.Event> drained = eb.drain();
        check("drain returns 4", drained.size() == 4, "got " + drained.size());
        check("drain clears the queue", eb.size() == 0, "size " + eb.size());

        EventBuffer.Event hurt = drained.get(0);
        check("event carries type+stateVersion+health",
                "entityHurt".equals(hurt.type) && hurt.stateVersion == 1 && hurt.health == 15.0,
                hurt.type + " v" + hurt.stateVersion + " h" + hurt.health);
        check("stateVersion increments monotonically",
                drained.get(1).stateVersion == 2 && drained.get(2).stateVersion == 3,
                "versions wrong");

        // TTL: an event injected via reflection is not testable without clock
        // control, but capacity trim is: spam 150 events, expect 100 kept.
        for (int i = 0; i < 150; i++) {
            eb.add("tick", null, null, null, null, null, null);
        }
        List<EventBuffer.Event> all = eb.drain();
        check("capacity trims to 100", all.size() == 100, "got " + all.size());
        check("oldest dropped, newest kept (stateVersion 154 is last)",
                all.get(all.size() - 1).stateVersion == 154,
                "last v" + all.get(all.size() - 1).stateVersion);

        // ── DeathMemory ──
        DeathMemory dm = new DeathMemory();
        check("no deaths -> last()==null", dm.last() == null, "not null");
        check("no deaths -> size()==0", dm.size() == 0, "size " + dm.size());

        dm.record(10, 64, -20, DeathMemory.inferCauseFromMessage("Steve drowned"), "Steve drowned");
        dm.record(12, 64, -22, DeathMemory.inferCauseFromMessage("Steve was slain by Zombie"), "Steve was slain by Zombie");
        dm.record(14, 70, -24, "fall", "Steve fell from a high place");

        check("last() is most recent", "fall".equals(dm.last().cause), dm.last().cause);
        check("size()==3", dm.size() == 3, "size " + dm.size());
        check("causeCounts sums per cause",
                dm.causeCounts().get("fall") == 1 && dm.causeCounts().get("mob") == 1
                        && dm.causeCounts().get("drowning") == 1,
                dm.causeCounts().toString());
        check("history oldest-first", dm.history().get(0).cause.equals("drowning"),
                dm.history().get(0).cause);
        check("ids increment", dm.history().get(2).id == 3, "id " + dm.history().get(2).id);

        // capacity: 10 more deaths -> only last 8 kept
        for (int i = 0; i < 10; i++) {
            dm.record(i, 60, i, "mob", null);
        }
        check("death ring trims to 8", dm.size() == 8, "size " + dm.size());
        check("causeCounts reflects only the ring",
                dm.causeCounts().get("mob") == 8 && !dm.causeCounts().containsKey("drowning"),
                dm.causeCounts().toString());

        // ── inferCauseFromMessage taxonomy ──
        String[][] cases = {
                {"Steve drowned", "drowning"},
                {"Steve tried to swim in lava", "lava"},
                {"Steve walked on magma", "lava"},
                {"Steve burned to death", "fire"},
                {"Steve fell from a high place", "fall"},
                {"Steve hit the ground too hard", "fall"},
                {"Steve was blown up by Creeper", "explosion"},
                {"Steve was slain by Zombie", "mob"},
                {"Steve starved to death", "starvation"},
                {"Steve was pricked to death by a cactus", "hazard_block"},
                {"Steve withered away", "wither"},
                {"Steve fell out of the world", "fall"}, // 'fell' check precedes the void branch
                {"Steve was consumed by the void", "void"},
                {null, "unknown"},
                {"something odd", "unknown"},
        };
        for (String[] c : cases) {
            String got = DeathMemory.inferCauseFromMessage(c[0]);
            check("infer(" + c[0] + ")==" + c[1], c[1].equals(got), "got " + got);
        }

        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
