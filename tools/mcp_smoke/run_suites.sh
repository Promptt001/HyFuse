#!/bin/bash
# Runs the offline test suites against the built classes.
#
# Self-contained (T1.6): generates the runtime classpath from Gradle via
# tools/mcp_smoke/printcp.gradle (never hand-pick jars — the minimal recipe
# misses fabric-loader + fabric-api modules, see HANDOVER.md §14) and
# compiles the suites into a work dir under /tmp. No /tmp state is assumed
# beyond what this script creates.
#
# Verdict per suite = the suite's process EXIT CODE (0 = pass, nonzero =
# fail). Every suite in this directory signals failure via System.exit(1)
# and prints a final verdict line. Output grepping was retired (T1.5):
# expected-error log lines (e.g. OpenApiTest's null-dispatcher routing
# proof, which logs an NPE stack trace by design) caused false negatives.
#
# Prereq: ./gradlew build (build/classes/java/client must exist).
# Usage: tools/mcp_smoke/run_suites.sh [repo-root]
# Env: JAVA_HOME (JDK 25; default /usr/lib/jvm/java-25-openjdk-amd64),
#      SMOKE_WORK (work dir; default /tmp/hyfuse_smoke).
set -u
ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
WORK="${SMOKE_WORK:-/tmp/hyfuse_smoke}"
JAVA="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
GRADLE="$ROOT/gradlew"

MOD_CLASSES="$ROOT/build/classes/java/client"
MOD_RES="$ROOT/build/resources/client"
[ -d "$MOD_CLASSES" ] || {
  echo "ERROR: $MOD_CLASSES missing — run ./gradlew build first (HANDOVER.md §14)"
  exit 2
}

mkdir -p "$WORK"
cd "$WORK"

# 1. Gradle-resolved client compile classpath (run from $ROOT — Gradle needs
# the real build; outputs land in $WORK).
JAVA_HOME="$JAVA" bash -c "cd '$ROOT' && '$GRADLE' --no-daemon -q printcp \
  --init-script '$ROOT/tools/mcp_smoke/printcp.gradle'" \
  >"$WORK/gradle_cp.out" 2>&1 || {
  echo "ERROR: gradle printcp failed:"; cat "$WORK/gradle_cp.out"; exit 2
}
CP=$(grep -m1 '^CLIENTCP=' "$WORK/gradle_cp.out" | cut -d= -f2-)
case "$CP" in
  ""|ERR:*) echo "ERROR: bad CLIENTCP line (got: ${CP:0:80})"; exit 2 ;;
esac

# 2. Compile the suites (deprecation notes on stderr are fine).
CP_ALL="$MOD_CLASSES:$MOD_RES:$CP"
javac -cp "$CP_ALL" -d "$WORK/classes" "$ROOT"/tools/mcp_smoke/*.java || {
  echo "ERROR: suite compilation failed"; exit 2
}
RUN="$WORK/classes:$CP_ALL"

# 3. Run suites; verdict = process exit code.
J="$JAVA/bin/java"
declare -A M=(
  [SlotMapTest]=com.hyfuse.bridge.dispatch.SlotMapTest
  [CraftResolverTest]=com.hyfuse.bridge.dispatch.CraftResolverTest
  [QueueTest]=com.hyfuse.bridge.dispatch.QueueTest
  [SenseTest]=SenseTest
  [SendChatGuardTest]=com.hyfuse.bridge.dispatch.SendChatGuardTest
  [Smoke]=Smoke
  [Smoke2]=Smoke2
  [OpenApiTest]=OpenApiTest
  [PlaybookTest]=com.hyfuse.bridge.sense.PlaybookTest
  [AgentLoopTest]=com.hyfuse.bridge.agent.AgentLoopTest
  [AgentGoalTest]=com.hyfuse.bridge.agent.AgentGoalTest
)
ORDER="SlotMapTest CraftResolverTest QueueTest SenseTest SendChatGuardTest Smoke Smoke2 OpenApiTest PlaybookTest AgentLoopTest AgentGoalTest"
pass=0; fail=0
LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT
for T in $ORDER; do
  echo "=== $T"
  "$J" -cp "$RUN" "${M[$T]}" >"$LOG" 2>&1
  RC=$?
  tail -2 "$LOG"
  if [ "$RC" -eq 0 ]; then
    pass=$((pass+1))
  else
    fail=$((fail+1))
    echo "[$T] FAILED (exit $RC) — full output:"
    cat "$LOG"
  fi
done
echo "SUITES: $pass pass, $fail fail (of ${#M[@]})"
[ $fail -eq 0 ]
