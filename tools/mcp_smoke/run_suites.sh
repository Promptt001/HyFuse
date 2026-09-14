#!/bin/bash
# Runs the offline test suites against the built classes.
#
# Verdict per suite = the suite's process EXIT CODE (0 = pass, nonzero =
# fail). Every suite in this directory signals failure via System.exit(1)
# and prints a final verdict line. Output grepping was retired (T1.5):
# expected-error log lines (e.g. OpenApiTest's null-dispatcher routing
# proof, which logs an NPE stack trace by design) caused false negatives.
#
# Usage: tools/mcp_smoke/run_suites.sh [repo-root]
# Repo root defaults to the parent of tools/; classpath from /tmp/mcp_smoke/CP.txt
# (CP must start with build/classes/java/client + build/resources/client,
#  followed by the Gradle-resolved dependency jars — see HANDOVER.md §14
#  for the verified printcp recipe. Do not hand-pick jars.)
set -u
ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd /tmp/mcp_smoke
CP=$(head -1 CP.txt | cut -d= -f2-)
RES="$ROOT/build/resources/client"
RUN="/tmp/mcp_smoke/outp9:$RES:$CP"
J="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}/bin/java"
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
