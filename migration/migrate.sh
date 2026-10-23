#!/usr/bin/env bash
#
# Moves a knowledge base from the bash ztk layout to ztk's, once, by fixed rules:
#
#   1. moves every root-level .md into zettel/, except the Progress log (progress*.md), the weekly statuses (step 2),
#      AGENTS.md, CLAUDE.md and 000-Project-Index.md;
#   2. moves Weekly-Status-YYYY-WW.md to summaries/YYYY-Www.md, the week shifted back one from 2026-42 on, since the
#      bash script skipped week 41;
#   3. sets the note attribute on each live task named by the task_uuid of exactly one note.
#
# Notes are never edited inside. Git is the safety net: commit the knowledge base first, run this, review
# 'git diff --stat -M', fix small things by hand, and reset to that commit if anything goes badly wrong.
# A case no rule settles is printed and left as it is.
#
# Usage: migrate.sh <knowledge-base>
#
# Taskwarrior is reached as usual, through TASKRC and TASKDATA when set; its data must live in the knowledge base,
# so a rehearsal on a copy never touches the real tasks.

set -euo pipefail

if [[ $# -ne 1 ]]; then
   echo "Usage: $0 <knowledge-base>" >&2
   exit 1
fi
base=$(realpath "$1")
cd "$base"

# The same overrides ztk passes, so Taskwarrior knows the attribute without touching .taskrc.
tw=(task rc.uda.note.type=string rc.uda.note.label=Note rc.confirmation=off rc.verbose=nothing)

location=$("${tw[@]}" _get rc.data.location)
location=$(realpath "${location/#\~/$HOME}")
if [[ $location != "$base" ]]; then
   echo "Taskwarrior's data is in $location, not in $base. Point TASKRC or TASKDATA at the knowledge base's." >&2
   exit 1
fi

# Root-level notes into zettel/.
mkdir -p zettel
moved=0
for file in *.md; do
   [[ -e $file ]] || continue
   case $file in
      progress*.md | Weekly-Status-*.md | AGENTS.md | CLAUDE.md | 000-Project-Index.md) continue ;;
   esac
   git mv "$file" "zettel/$file"
   moved=$((moved + 1))
done
echo "Moved $moved root notes into zettel/."

# Weekly statuses into summaries/, named after the ISO week.
mkdir -p summaries
moved=0
for file in Weekly-Status-*.md; do
   [[ -e $file ]] || continue
   if [[ ! $file =~ ^Weekly-Status-([0-9]{4})-([0-9]{2})\.md$ ]]; then
      echo "Skipped $file: not named Weekly-Status-YYYY-WW.md." >&2
      continue
   fi
   year=${BASH_REMATCH[1]}
   week=$((10#${BASH_REMATCH[2]}))
   if [[ $year == 2026 && $week -ge 42 ]]; then
      week=$((week - 1))
   fi
   git mv "$file" "$(printf 'summaries/%s-W%02d.md' "$year" "$week")"
   moved=$((moved + 1))
done
echo "Moved $moved weekly statuses into summaries/."

# Tasks linked to their notes. Each note's task_uuid, read from its frontmatter only, as "<uuid> <path>".
# Every note on disk, tracked or not.
notes=$(find . -name '*.md' -not -path './.git/*' -printf '%P\0' | xargs -0 awk '
   FNR == 1 { inside = ($0 == "---") ; next }
   inside && $0 == "---" { inside = 0 ; nextfile }
   inside && $1 == "task_uuid:" && $2 != "" { print $2, FILENAME ; nextfile }
' | sort)
declare -A count path
while read -r uuid file; do
   [[ -n $uuid ]] || continue
   count[$uuid]=$((${count[$uuid]:-0} + 1))
   path[$uuid]=$file
done <<< "$notes"

declare -A live
linked=0
# Fields split by the unit separator: a tab is whitespace to read, which would merge an empty note with the next field.
while IFS=$'\x1f' read -r uuid status note description; do
   live[$uuid]=1
   [[ -z $note ]] || continue
   case ${count[$uuid]:-0} in
      0) echo "No note for $status task $uuid ($description)." >&2 ;;
      1) "${tw[@]}" "$uuid" modify "note:${path[$uuid]}"
         linked=$((linked + 1)) ;;
      *) echo "Several notes for $status task $uuid ($description), none linked:" >&2
         grep "^$uuid " <<< "$notes" | cut -d' ' -f2- | sed 's/^/   /' >&2 ;;
   esac
done < <("${tw[@]}" rc.json.array=on export \
   | jq -r '.[] | select(.status != "deleted") | [.uuid, .status, (.note // ""), .description] | join("\u001f")')
echo "Linked $linked tasks to their notes."

for uuid in "${!count[@]}"; do
   [[ -n ${live[$uuid]:-} ]] || echo "Note ${path[$uuid]} names task $uuid, which is deleted or missing; left as it is." >&2
done
