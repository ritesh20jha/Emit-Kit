#!/bin/sh
# SBX-02 sandbox check contract.
# Input:  $WORK_DIR/in.tar holding src/** and languages.txt (one of typescript|python|java per line).
# Output: $WORK_DIR/out.tar (formatted src/**) and $WORK_DIR/report.json.
# Exit:   0 when the checks ran (tool failures are recorded in the report); 64 on bad input.
# WORK_DIR and VENDOR_DIR must be absolute paths.
set -u

WORK_DIR=${WORK_DIR:-/work}
VENDOR_DIR=${VENDOR_DIR:-/opt/vendor}
SRC=$WORK_DIR/src
OUT_CAP=65536

export CI=1
export RUFF_NO_CACHE=true
unset TSC_WATCHFILE TSC_WATCHDIRECTORY

die() {
  echo "check.sh: $1" >&2
  exit 64
}

# The rootfs is read-only and only WORK_DIR is writable, so scratch lives there. It sits outside src/, so out.tar omits it.
TMP=$(mktemp -d "$WORK_DIR/.check.XXXXXX") || exit 1
trap 'rm -rf "$TMP"' EXIT
export TMPDIR="$TMP"

[ -f "$WORK_DIR/in.tar" ] || die "missing $WORK_DIR/in.tar"
tar -xf "$WORK_DIR/in.tar" -C "$WORK_DIR" || die "cannot extract $WORK_DIR/in.tar"
[ -f "$WORK_DIR/languages.txt" ] || die "missing languages.txt in in.tar"
mkdir -p "$SRC"

want_ts=0 want_py=0 want_java=0
while IFS= read -r lang || [ -n "$lang" ]; do
  case $lang in
    '') ;;
    typescript) want_ts=1 ;;
    python) want_py=1 ;;
    java) want_java=1 ;;
    *) die "unknown language: $lang" ;;
  esac
done < "$WORK_DIR/languages.txt"

# json_escape: stdin -> JSON string body, capped at OUT_CAP bytes (never ends mid-escape).
# Input is bounded to 2*OUT_CAP before the line join: escaping at most doubles the size, so the cap still fills.
json_escape() {
  tab=$(printf '\t')
  cr=$(printf '\r')
  tr -d '\000-\010\013\014\016-\037' |
    head -c $((OUT_CAP * 2)) |
    sed -e ':a' -e '$!N' -e '$!ba' \
      -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e "s/$tab/\\\\t/g" -e "s/$cr/\\\\r/g" -e 's/\n/\\n/g' |
    head -c "$OUT_CAP" |
    sed -E 's/(^|[^\\])((\\\\)*)\\$/\1\2/'
}

# dirs_with NAME... : prints the sorted, unique dirs under $SRC holding any NAME (relative, "." for root).
dirs_with() {
  for n in "$@"; do
    (cd "$SRC" && find . -type f -name "$n" -exec dirname {} \;)
  done | sed 's|^\./||' | sort -u
}

RESULTS=$TMP/results
: > "$RESULTS"
COUNT=0

# record TOOL DIR CODE OUTFILE : appends one report entry.
record() {
  sep=
  [ "$COUNT" -eq 0 ] || sep=,
  COUNT=$((COUNT + 1))
  printf '%s{"tool":"%s","dir":"%s","exitCode":%s,"output":"%s"}' \
    "$sep" "$1" "$(printf '%s' "$2" | json_escape)" "$3" "$(json_escape < "$4")" >> "$RESULTS"
}

# run_tool TOOL DIR CMD... : runs CMD in $SRC/DIR and appends one report entry.
run_tool() {
  tool=$1 dir=$2
  shift 2
  (cd "$SRC/$dir" && "$@") < /dev/null > "$TMP/tool-out" 2>&1
  record "$tool" "$dir" "$?" "$TMP/tool-out"
}

# no_project TOOL LANG MARKER : appends a failed entry for a requested language that has no project dir.
no_project() {
  printf '%s requested but no %s under src/' "$2" "$3" > "$TMP/tool-out"
  record "$1" . 1 "$TMP/tool-out"
}

if [ "$want_ts" -eq 1 ]; then
  dirs_with tsconfig.json > "$TMP/dirs"
  [ -s "$TMP/dirs" ] || no_project tsc typescript tsconfig.json
  while IFS= read -r d; do
    run_tool prettier "$d" prettier --write .
    run_tool tsc "$d" tsc --noEmit --strict -p .
  done < "$TMP/dirs"
fi

if [ "$want_py" -eq 1 ]; then
  dirs_with pyproject.toml > "$TMP/dirs"
  [ -s "$TMP/dirs" ] || no_project "ruff check" python pyproject.toml
  while IFS= read -r d; do
    run_tool "ruff format" "$d" ruff format .
    run_tool "ruff check" "$d" ruff check --fix .
  done < "$TMP/dirs"
fi

if [ "$want_java" -eq 1 ]; then
  (cd "$SRC" && find . -type f -name '*.java' | sort) > "$TMP/java"
  if [ -s "$TMP/java" ]; then
    set --
    while IFS= read -r f; do set -- "$@" "$f"; done < "$TMP/java"
    run_tool google-java-format . google-java-format --replace "$@"

    dirs_with pom.xml build.gradle build.gradle.kts > "$TMP/dirs"
    [ -s "$TMP/dirs" ] || echo . > "$TMP/dirs"
    mkdir -p "$WORK_DIR/classes"
    while IFS= read -r d; do
      (cd "$SRC/$d" && find . -type f -name '*.java' | sort) > "$TMP/jdir"
      [ -s "$TMP/jdir" ] || continue
      set --
      while IFS= read -r f; do set -- "$@" "$f"; done < "$TMP/jdir"
      run_tool javac "$d" javac -d "$WORK_DIR/classes" -cp "$VENDOR_DIR/*" "$@"
    done < "$TMP/dirs"
  else
    no_project javac java ".java files"
  fi
fi

tar -cf "$WORK_DIR/out.tar" -C "$WORK_DIR" src || exit 1
{
  printf '{"results":['
  cat "$RESULTS"
  printf ']}\n'
} > "$WORK_DIR/report.json"
