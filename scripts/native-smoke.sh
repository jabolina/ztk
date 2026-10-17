#!/usr/bin/env bash
# Smoke test for the native ztk binary, against a throwaway Taskwarrior and knowledge base. Never touches real data.
#
# Usage: scripts/native-smoke.sh [binary]   (default: target/ztk2)
set -euo pipefail

BIN=$(realpath "${1:-target/ztk2}")
[[ -x "$BIN" ]] || { echo "No executable at $BIN. Build it with: mvn -q package -Pnative -DskipTests"; exit 1; }

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/taskdata" "$T/notes"
printf 'data.location=%s\nconfirmation=off\nverbose=nothing\nnews.version=99.0.0\n' "$T/taskdata" > "$T/taskrc"
printf 'data.location=%s\ntask.rc=%s\ntask.data=%s\neditor=true\n' "$T/notes" "$T/taskrc" "$T/taskdata" > "$T/ztkrc"
# ztk reads its own configuration; Taskwarrior run directly by anything else must not find the real data either.
export ZTKRC="$T/ztkrc" HOME="$T" TASKRC="$T/taskrc" TASKDATA="$T/taskdata"

fail() { echo "FAIL: $*"; exit 1; }
check() { echo "+ ztk ${*:2}"; "$@" >/dev/null || fail "ztk ${*:2}"; }

# Help and argument handling.
check "$BIN" --help
check "$BIN" add --help
if "$BIN" add x --force project:Smoke >/dev/null 2>&1; then fail "add accepted an unknown option"; fi

# Piped output is JSON, which needs the terminal detection to work in the native image.
echo "+ ztk config show | (piped)"
[[ $(echo | "$BIN" config show | head -c1) == "{" ]] || fail "piped config show did not print JSON"

# Task lifecycle. Piped output is JSON; commands producing a note report its path in "path".
echo "+ ztk add / start / current / stop / done"
NOTE=$("$BIN" add "smoke task" project:Smoke +week | jq -r .path)
[[ -f "$NOTE" ]] || fail "add did not report the note path: $NOTE"
"$BIN" add "second task" project:Smoke >/dev/null
STARTED=$("$BIN" start 1 | jq -r .path)
[[ "$STARTED" == "$NOTE" ]] || fail "start reported $STARTED, expected $NOTE"
check "$BIN" current
check "$BIN" week
check "$BIN" list Smoke
check "$BIN" info 1
check "$BIN" stop
ZETTEL=$("$BIN" new "Smoke zettel" | jq -r .path)
[[ -f "$ZETTEL" ]] || fail "new did not report the zettel path: $ZETTEL"

# Completion: the completers are created by Æsh and must reach Taskwarrior in the native image too.
echo "+ ztk --aesh-complete"
# Æsh always adds its file sentinel for commands without a positional argument, the root included.
[[ $("$BIN" --aesh-complete -- st | grep -v '^__aesh_' | cut -f1 | tr '\n' ' ') == "start stop " ]] \
   || fail "subcommands not completed"
[[ $("$BIN" --aesh-complete -- done "" | tr '\n' ' ') == "1 2 " ]] || fail "task IDs not completed"
[[ $("$BIN" --aesh-complete -- add x project:Sm) == "project:Smoke" ]] || fail "project attribute not completed"
[[ $("$BIN" --aesh-complete -- add x +we) == "+week" ]] || fail "tag not completed"
[[ $("$BIN" --aesh-complete -- list Sm) == "Smoke" ]] || fail "project name not completed"
"$BIN" --aesh-completion zsh | grep -q -- "--aesh-complete" || fail "no zsh completion script"

check "$BIN" done 1

# Completion runs on every Tab: each call must stay well under what a person notices.
per_call() {
   local start
   start=$(date +%s%N)
   for _ in $(seq 20); do "$BIN" --aesh-complete -- "$@" >/dev/null; done
   echo $(( ($(date +%s%N) - start) / 20000000 ))
}
SUBCOMMANDS=$(per_call st)
IDS=$(per_call done "")
echo "completion: ${SUBCOMMANDS} ms/call for subcommands, ${IDS} ms/call for task IDs"
(( SUBCOMMANDS <= 50 && IDS <= 50 )) || fail "completion slower than 50 ms per call"

echo "OK"
