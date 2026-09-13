#!/bin/bash
# Runs the offline test suites against the built classes.
# Usage: tools/mcp_smoke/run_suites.sh [repo-root]
# Repo root defaults to the parent of tools/; classpath from /tmp/mcp_smoke/CP.txt
# (see scripts/ or docs/CODEBASE_REFERENCE.md for the classpath recipe).
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
for T in $ORDER; do
  echo "=== $T"
  OUT=$($J -cp "$RUN" "${M[$T]}" 2>&1)
  echo "$OUT" | tail -2
  if echo "$OUT" | grep -qE "FAIL|Error:|Exception"; then fail=$((fail+1)); else pass=$((pass+1)); fi
done
echo "SUITES: $pass pass, $fail fail (of ${#M[@]})"
[ $fail -eq 0 ]
