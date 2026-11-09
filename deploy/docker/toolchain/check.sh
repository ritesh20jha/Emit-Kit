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
unset TSC_WATCHFILE TSC_WATCHDIRECTORY

die() {
  echo "check.sh: $1" >&2
  exit 64
}

TMP=$(mktemp -d "${TMPDIR:-/tmp}/check.XXXXXX") || exit 1
trap 'rm -rf "$TMP"' EXIT

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
json_escape() {
  tab=$(printf '\t')
  cr=$(printf '\r')
  tr -d '\000-\010\013\014\016-\037' |
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

# run_tool TOOL DIR CMD... : runs CMD in $SRC/DIR and appends one report entry.
run_tool() {
  tool=$1 dir=$2
  shift 2
  (cd "$SRC/$dir" && "$@") < /dev/null > "$TMP/tool-out" 2>&1
  code=$?
  sep=
  [ "$COUNT" -eq 0 ] || sep=,
  COUNT=$((COUNT + 1))
  printf '%s{"tool":"%s","dir":"%s","exitCode":%s,"output":"%s"}' \
    "$sep" "$tool" "$(printf '%s' "$dir" | json_escape)" "$code" "$(json_escape < "$TMP/tool-out")" >> "$RESULTS"
}

if [ "$want_ts" -eq 1 ]; then
  dirs_with tsconfig.json > "$TMP/dirs"
  while IFS= read -r d; do
    run_tool prettier "$d" prettier --write .
    run_tool tsc "$d" tsc --noEmit --strict -p .
  done < "$TMP/dirs"
fi

if [ "$want_py" -eq 1 ]; then
  dirs_with pyproject.toml > "$TMP/dirs"
  while IFS= read -r d; do
    run_tool "ruff format" "$d" ruff format .
    run_tool "ruff check" "$d" ruff check --fix .
  done < "$TMP/dirs"
fi

if [ "$want_java" -eq 1 ]; then
  (cd "$SRC" && find . -type f -name '*.java' | sed 's|^\./||' | sort) > "$TMP/java"
  if [ -s "$TMP/java" ]; then
    set --
    while IFS= read -r f; do set -- "$@" "$f"; done < "$TMP/java"
    run_tool google-java-format . google-java-format --replace "$@"

    dirs_with pom.xml build.gradle build.gradle.kts > "$TMP/dirs"
    [ -s "$TMP/dirs" ] || echo . > "$TMP/dirs"
    mkdir -p "$WORK_DIR/classes"
    while IFS= read -r d; do
      (cd "$SRC/$d" && find . -type f -name '*.java' | sed 's|^\./||' | sort) > "$TMP/jdir"
      [ -s "$TMP/jdir" ] || continue
      set --
      while IFS= read -r f; do set -- "$@" "$f"; done < "$TMP/jdir"
      run_tool javac "$d" javac -d "$WORK_DIR/classes" -cp "$VENDOR_DIR/*" "$@"
    done < "$TMP/dirs"
  fi
fi

tar -cf "$WORK_DIR/out.tar" -C "$WORK_DIR" src || exit 1
{
  printf '{"results":['
  cat "$RESULTS"
  printf ']}\n'
} > "$WORK_DIR/report.json"
