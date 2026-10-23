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

# Task lifecycle. Piped output is JSON; a task reports its note in "note", other commands producing a file in "path".
echo "+ ztk add / start / current / stop / done"
NOTE=$("$BIN" add "smoke task" project:Smoke +week | jq -r .note)
[[ -f "$NOTE" ]] || fail "add did not report the note path: $NOTE"
"$BIN" add "second task" project:Smoke >/dev/null
STARTED=$("$BIN" start 1 | jq -r .note)
[[ "$STARTED" == "$NOTE" ]] || fail "start reported $STARTED, expected $NOTE"
check "$BIN" current
check "$BIN" week
check "$BIN" list Smoke
check "$BIN" info 1
check "$BIN" stop
ZETTEL=$("$BIN" new "Smoke zettel" | jq -r .path)
[[ -f "$ZETTEL" ]] || fail "new did not report the zettel path: $ZETTEL"

# paper new fills its built-in template, which must be packed into the native image.
echo "+ ztk paper new"
printf '%%PDF-1.4\n' > "$T/raft.pdf"
PAPER=$("$BIN" paper new "$T/raft.pdf" --title "Smoke Paper" --venue ATC --year 2014 | jq -r .path)
[[ -f "$PAPER" ]] || fail "paper new did not report the note path: $PAPER"

# A knowledge base of 1,000 linked notes, for reading it and for timing.
mkdir -p "$T/notes/zettel"
for i in $(seq 1000); do
   printf -- '---\ntitle: Note %d\n---\nOn lamport clocks and consensus, part %d.\nSee [[note-%d]] and [[note-%d]].\n' \
      "$i" "$i" $(( i % 1000 + 1 )) $(( (i + 6) % 1000 + 1 )) > "$T/notes/zettel/note-$i.md"
done
echo "+ ztk search / graph / check"
(( $("$BIN" search lamport clocks | jq '.results | length') > 0 )) || fail "search found nothing"
(( $("$BIN" graph note-1 | jq '.nodes | length') > 1 )) || fail "graph found no neighbors"
"$BIN" check >/dev/null || fail "check reported errors in a knowledge base without any"

# A plan for this year and quarter: the goal templates are packed into the native image, and new goals are numbered.
YEAR=$(date +%Y)
QUARTER="$YEAR-Q$(( ($(date +%-m) - 1) / 3 + 1 ))"
echo "+ ztk goal new / effort new / add --effort / effort assign"
YEARLY=$("$BIN" goal new "$YEAR" --title "Smoke yearly goal" | jq -r .path)
[[ -f "$YEARLY" && $(basename "$YEARLY") == "$YEAR-G1-"* ]] || fail "goal new did not report the yearly goal: $YEARLY"
QUARTERLY=$("$BIN" goal new "$QUARTER" --title "Smoke quarterly goal" --parent "$YEAR-G1" | jq -r .path)
[[ -f "$QUARTERLY" && $(basename "$QUARTERLY") == "$QUARTER-G1-"* ]] || fail "goal new did not report the quarterly goal: $QUARTERLY"
EFFORT=$("$BIN" effort new smoke-effort --title "Smoke effort" --goal "$QUARTER-G1" | jq -r .path)
[[ -f "$EFFORT" ]] || fail "effort new did not report the effort: $EFFORT"
check "$BIN" effort set smoke-effort --status active
check "$BIN" add "effort task" project:Smoke --effort smoke-effort
check "$BIN" effort assign smoke-effort 2
echo "+ ztk goal list / effort info / graph / check"
[[ $("$BIN" goal list | jq --arg id "goal:$QUARTER-G1" '.goals[] | select(.id == $id) | .efforts') == 1 ]] \
   || fail "goal list did not count the quarterly goal's effort"
(( $("$BIN" effort info smoke-effort | jq '.tasks.pending | length') == 2 )) || fail "effort info did not list both tasks"
"$BIN" graph "$YEAR-G1" | jq -e '.nodes[] | select(.id == "effort:smoke-effort")' >/dev/null \
   || fail "graph did not reach the effort from the yearly goal"
"$BIN" check >/dev/null || fail "check reported errors in a plan without any"

# Completion: the completers are created by Æsh and must reach Taskwarrior in the native image too.
echo "+ ztk --aesh-complete"
# Æsh always adds its file sentinel for commands without a positional argument, the root included.
[[ $("$BIN" --aesh-complete -- st | grep -v '^__aesh_' | cut -f1 | tr '\n' ' ') == "start stop " ]] \
   || fail "subcommands not completed"
[[ $("$BIN" --aesh-complete -- done "" | tr '\n' ' ') == "1 2 3 " ]] || fail "task IDs not completed"
[[ $("$BIN" --aesh-complete -- session add "" | tr '\n' ' ') == "1 2 3 " ]] || fail "task IDs not completed for session add"
[[ -z $("$BIN" --aesh-complete -- summary "" | grep '^__aesh_') ]] || fail "summary offered file names for its period"
[[ $("$BIN" --aesh-complete -- add x project:Sm) == "project:Smoke" ]] || fail "project attribute not completed"
[[ $("$BIN" --aesh-complete -- add x +we) == "+week" ]] || fail "tag not completed"
[[ $("$BIN" --aesh-complete -- list Sm) == "Smoke" ]] || fail "project name not completed"
[[ $("$BIN" --aesh-complete -- graph note-100 | tr '\n' ' ') == "note-100 note-1000 " ]] || fail "note names not completed"
[[ $("$BIN" --aesh-complete -- graph note-1 --kind note,t) == "note,task" ]] || fail "comma list not completed as a whole word"
[[ $("$BIN" --aesh-complete -- goal list --status "" | tr '\n' ' ') == "dropped missed open reached " ]] \
   || fail "goal statuses not completed"
[[ $("$BIN" --aesh-complete -- goal new "$QUARTER" --title x --parent "") == "$YEAR-G1" ]] || fail "yearly goals not completed"
[[ $("$BIN" --aesh-complete -- effort new x --title x --goal "") == "$QUARTER-G1" ]] || fail "quarterly goals not completed"
[[ $("$BIN" --aesh-complete -- add x project:Smoke --effort "") == "smoke-effort" ]] || fail "open efforts not completed"
"$BIN" --aesh-completion zsh | grep -q -- "--aesh-complete" || fail "no zsh completion script"

# Sessions: one part read from stdin, which the native image reads from the process, not from Æsh's console.
echo "+ ztk session add / info"
SESSION=$(printf 'Wrote the smoke test\n' | "$BIN" session add 2 --title "Smoke session" --done - --now "Script runs" \
   --next "Time it" | jq -r .path)
[[ -f "$SESSION" ]] || fail "session add did not report the journal file: $SESSION"
"$BIN" info 2 | jq -e '.sessions[] | select(.done == "Wrote the smoke test")' >/dev/null \
   || fail "info did not show the session read from stdin"

# A journal of 300 days, three sessions a day on the effort's task. A task entered 300 days ago with one session the
# day after has fewer than info's three, so info reads every day back to the task's entry: its slowest case.
WORKED=$(task _get 3.uuid)
OLD=00000000-0000-4000-8000-000000000300
ENTRY=$(date -u -d "300 days ago" +%Y%m%dT%H%M%SZ)
printf '{"uuid":"%s","description":"old task","status":"pending","project":"Smoke","entry":"%s","modified":"%s"}\n' \
   "$OLD" "$ENTRY" "$ENTRY" | task import >/dev/null
for days in $(seq 300 -1 1); do
   DAY=$(date -d "$days days ago" +%F)
   {
      printf '# %s\n' "$DAY"
      [[ $days == 299 ]] && printf '\n## [[%s]] · Old session\n**Done:** Started\n**Now:** Waiting\n**Next:** Pick it up\n' "$OLD"
      for n in 1 2 3; do
         printf '\n## [[%s]] · Session %d\n**Done:** Part %d of the effort\n**Now:** Halfway\n**Next:** The next part\n' \
            "$WORKED" "$n" "$n"
      done
   } > "$T/notes/journal/$DAY.md"
done
(( $("$BIN" info "$OLD" | jq '.sessions | length') == 1 )) || fail "info did not find the old task's only session"

# Summaries: the previous week, and this quarter and year by plan; facts are printed for an open period too.
LAST_WEEK=$(date -d "7 days ago" +%G-W%V)
if (( $(date +%-m) <= 3 )); then LAST_QUARTER="$(( YEAR - 1 ))-Q4"; else LAST_QUARTER="$YEAR-Q$(( ($(date +%-m) - 1) / 3 ))"; fi
echo "+ ztk summary / summary $QUARTER / summary $YEAR"
"$BIN" summary | jq -e --arg week "$LAST_WEEK" '.period == $week and any(.projects[].progress[]; .sessions | length > 0)' \
   >/dev/null || fail "summary did not give last week's sessions"
"$BIN" summary "$QUARTER" | jq -e --arg id "goal:$QUARTER-G1" '.goals[] | select(.id == $id)
   | .efforts[] | select(.id == "effort:smoke-effort") | .tasks | length > 0' >/dev/null \
   || fail "summary $QUARTER did not list the effort's tasks under its goal"
"$BIN" summary "$YEAR" | jq -e --arg id "goal:$QUARTER-G1" '.goals[].goals[] | select(.id == $id)' >/dev/null \
   || fail "summary $YEAR did not nest the quarterly goal under the yearly goal"
echo "+ ztk summary --write --narrative -"
WEEKLY=$(printf 'Smoke narrative\n' | "$BIN" summary --write --narrative - | jq -r .path)
grep -q "Smoke narrative" "$WEEKLY" || fail "summary --write did not save the narrative: $WEEKLY"
if "$BIN" summary --write >/dev/null 2>&1; then fail "summary --write overwrote a written summary"; fi
# Last quarter's only goal is this quarter's, listed for its effort's sessions then; it has not ended, so none needs closing.
QUARTERLY_SUMMARY=$("$BIN" summary "$LAST_QUARTER" --write | jq -r .path)
grep -q "^period: $LAST_QUARTER" "$QUARTERLY_SUMMARY" || fail "summary $LAST_QUARTER --write did not save its frontmatter"
if "$BIN" summary "$QUARTER" --write >/dev/null 2>&1; then fail "summary $QUARTER --write saved a quarter not ended"; fi

check "$BIN" done 1

# Average milliseconds per run of a command; its failures are caught above, not here.
per_run() {
   local runs=$1 start
   shift
   start=$(date +%s%N)
   for _ in $(seq "$runs"); do "$@" >/dev/null 2>&1 || true; done
   echo $(( ($(date +%s%N) - start) / runs / 1000000 ))
}

# Completion runs on every Tab: each call must stay well under what a person notices.
SUBCOMMANDS=$(per_run 20 "$BIN" --aesh-complete -- st)
IDS=$(per_run 20 "$BIN" --aesh-complete -- done "")
NODES=$(per_run 20 "$BIN" --aesh-complete -- graph "")
GOALS=$(per_run 20 "$BIN" --aesh-complete -- effort new x --title x --goal "")
EFFORTS=$(per_run 20 "$BIN" --aesh-complete -- add x project:Smoke --effort "")
SESSIONS=$(per_run 20 "$BIN" --aesh-complete -- session add "")
echo "completion: ${SUBCOMMANDS} ms/call for subcommands, ${IDS} ms/call for task IDs, ${NODES} ms/call for graph nodes," \
   "${GOALS} ms/call for goals, ${EFFORTS} ms/call for efforts, ${SESSIONS} ms/call for session add"
(( SUBCOMMANDS <= 50 && IDS <= 50 && NODES <= 50 && GOALS <= 50 && EFFORTS <= 50 && SESSIONS <= 50 )) \
   || fail "completion slower than 50 ms per call"

# info reads the journal back to the old task's entry; summaries read Taskwarrior, the period's journal and the plan.
INFO=$(per_run 5 "$BIN" info "$OLD")
WEEK_SUMMARY=$(per_run 5 "$BIN" summary)
QUARTER_SUMMARY=$(per_run 5 "$BIN" summary "$QUARTER")
echo "300 journal days: ${INFO} ms for info, ${WEEK_SUMMARY} ms for a weekly summary, ${QUARTER_SUMMARY} ms for a quarter's"
(( INFO <= 300 && WEEK_SUMMARY <= 300 && QUARTER_SUMMARY <= 300 )) || fail "info or summary slower than 300 ms"

# The plan commands read goals, efforts and their tasks; graph from a goal also reads the 1,000 notes.
GOAL_LIST=$(per_run 5 "$BIN" goal list)
EFFORT_INFO=$(per_run 5 "$BIN" effort info smoke-effort)
GOAL_GRAPH=$(per_run 5 "$BIN" graph "$YEAR-G1")
echo "plan: ${GOAL_LIST} ms for goal list, ${EFFORT_INFO} ms for effort info, ${GOAL_GRAPH} ms for graph from a goal"
(( GOAL_LIST <= 300 && EFFORT_INFO <= 300 && GOAL_GRAPH <= 300 )) || fail "goal list, effort info or graph slower than 300 ms"

# search and graph read every note, so they must stay quick on a knowledge base of 1,000 notes.
SEARCH=$(per_run 5 "$BIN" search lamport clocks)
GRAPH=$(per_run 5 "$BIN" graph note-1)
echo "1,000 notes: ${SEARCH} ms for search, ${GRAPH} ms for graph"
(( SEARCH <= 300 && GRAPH <= 300 )) || fail "search or graph slower than 300 ms"

echo "OK"
