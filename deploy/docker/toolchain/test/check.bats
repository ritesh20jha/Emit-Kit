#!/usr/bin/env bats
# Contract tests for deploy/docker/toolchain/check.sh (SBX-02). Tools are stubs from test/stubs.

setup() {
  TOOLCHAIN=$(cd "$BATS_TEST_DIRNAME/.." && pwd)
  CHECK=$TOOLCHAIN/check.sh
  export WORK_DIR=$BATS_TEST_TMPDIR/work
  export VENDOR_DIR=$BATS_TEST_TMPDIR/vendor
  mkdir -p "$WORK_DIR" "$VENDOR_DIR"
  PATH=$TOOLCHAIN/test/stubs:$PATH
  export PATH
}

# make_input LANG... : packs a fixture src tree and languages.txt into $WORK_DIR/in.tar.
# DROP=<path under src> removes one fixture file first.
make_input() {
  local stage=$BATS_TEST_TMPDIR/stage
  rm -rf "$stage"
  mkdir -p "$stage/src/sdk/typescript" "$stage/src/sdk/python" "$stage/src/sdk/java"
  echo '{}' > "$stage/src/sdk/typescript/tsconfig.json"
  echo 'export const a = 1;' > "$stage/src/sdk/typescript/index.ts"
  echo '[project]' > "$stage/src/sdk/python/pyproject.toml"
  echo 'x = 1' > "$stage/src/sdk/python/main.py"
  echo '<project/>' > "$stage/src/sdk/java/pom.xml"
  echo 'class Foo {}' > "$stage/src/sdk/java/Foo.java"
  [ -z "${DROP:-}" ] || rm -f "$stage/src/$DROP"
  printf '%s\n' "$@" > "$stage/languages.txt"
  tar -cf "$WORK_DIR/in.tar" -C "$stage" src languages.txt
}

# run_check LANG... : builds the input for those languages and runs check.sh.
run_check() {
  make_input "$@"
  run "$CHECK"
}

assert_status() { [ "$status" -eq "$1" ]; }
tool_exit() { jq -r --arg t "$1" '.results[] | select(.tool == $t) | .exitCode' "$WORK_DIR/report.json"; }
tool_output() { jq -j --arg t "$1" '.results[] | select(.tool == $t) | .output' "$WORK_DIR/report.json"; }
assert_tool_exit() { [ "$(tool_exit "$1")" = "$2" ]; }
assert_report_all_zero() { jq -e '.results | length == 6 and all(.exitCode == 0)' "$WORK_DIR/report.json" >/dev/null; }
# assert_only_entry TOOL OUTPUT : the report holds exactly one failed entry, for dir ".", with OUTPUT.
assert_only_entry() {
  jq -e --arg t "$1" --arg o "$2" '.results == [{tool: $t, dir: ".", exitCode: 1, output: $o}]' "$WORK_DIR/report.json" >/dev/null
}
# block TOOL : prints the argv logged by the TOOL stub, one arg per line, without its --- separator.
block() { awk -v t="$1" '$0 == t { on = 1; next } $0 == "---" { on = 0 } on' "$STUB_LOG"; }

@test "all tools pass → report has zero exit codes" {
  run_check typescript python java
  assert_status 0
  assert_report_all_zero
  jq -e '.results[] | select(.tool == "tsc") | .dir == "sdk/typescript"' "$WORK_DIR/report.json" >/dev/null
}

@test "tsc fails → report records tsc exit 2, script exits 0" {
  STUB_TSC_EXIT=2 run_check typescript
  assert_status 0
  assert_tool_exit tsc 2
}

@test "missing in.tar → script exits 64" {
  run "$CHECK"
  assert_status 64
}

@test "unknown language line → script exits 64" {
  run_check typescript cobol
  assert_status 64
}

@test "output capped at 64KB" {
  STUB_TSC_BYTES=100000 run_check typescript
  assert_status 0
  [ "$(tool_output tsc | wc -c)" -eq 65536 ]
}

@test "huge tool output still capped at 64KB" {
  STUB_TSC_BYTES=5000000 run_check typescript
  assert_status 0
  [ "$(tool_output tsc | wc -c)" -eq 65536 ]
}

@test "tool output is JSON-escaped" {
  STUB_TSC_OUTPUT='say "hi"\\ path\nline2\ttab' run_check typescript
  assert_status 0
  jq -e . "$WORK_DIR/report.json" >/dev/null
  [ "$(tool_output tsc)" = "$(printf 'say "hi"\\ path\nline2\ttab')" ]
}

@test "formatted files land in out.tar" {
  run_check typescript python java
  assert_status 0
  tar -xOf "$WORK_DIR/out.tar" src/sdk/typescript/index.ts | grep -q formatted-by-stub
  tar -xOf "$WORK_DIR/out.tar" src/sdk/python/main.py | grep -q formatted-by-stub
  tar -xOf "$WORK_DIR/out.tar" src/sdk/java/Foo.java | grep -q formatted-by-stub
}

@test "file args start with ./ and javac gets -d and the quoted classpath" {
  local stage=$BATS_TEST_TMPDIR/stage
  mkdir -p "$stage/src/sdk/java"
  echo '<project/>' > "$stage/src/sdk/java/pom.xml"
  : > "$stage/src/sdk/java/-x.java"
  : > "$stage/src/sdk/java/@x.java"
  echo 'class Ok {}' > "$stage/src/sdk/java/Ok.java"
  echo java > "$stage/languages.txt"
  tar -cf "$WORK_DIR/in.tar" -C "$stage" src languages.txt
  touch "$VENDOR_DIR/a.jar"
  export STUB_LOG=$BATS_TEST_TMPDIR/argv.log
  run "$CHECK"
  assert_status 0
  [ "$(grep -h '\.java$' "$STUB_LOG" | grep -vc '^\./')" -eq 0 ]
  block google-java-format | grep -Fxq -- ./sdk/java/-x.java
  block google-java-format | grep -Fxq -- ./sdk/java/@x.java
  block javac | grep -Fxq -- ./-x.java
  [ "$(block javac | sed -n 1,4p)" = "$(printf '%s\n' -d "$WORK_DIR/classes" -cp "$VENDOR_DIR/*")" ]
}

@test "two SDK dirs with pom.xml → two javac entries with their dirs" {
  local stage=$BATS_TEST_TMPDIR/stage
  mkdir -p "$stage/src/sdk/java" "$stage/src/sdk/java2"
  for d in java java2; do
    echo '<project/>' > "$stage/src/sdk/$d/pom.xml"
    echo 'class A {}' > "$stage/src/sdk/$d/A.java"
  done
  echo java > "$stage/languages.txt"
  tar -cf "$WORK_DIR/in.tar" -C "$stage" src languages.txt
  run "$CHECK"
  assert_status 0
  jq -e '[.results[] | select(.tool == "javac") | .dir] == ["sdk/java", "sdk/java2"]' "$WORK_DIR/report.json" >/dev/null
}

@test "typescript without tsconfig.json → failed tsc entry, script exits 0" {
  DROP=sdk/typescript/tsconfig.json run_check typescript
  assert_status 0
  assert_only_entry tsc "typescript requested but no tsconfig.json under src/"
}

@test "python without pyproject.toml → failed ruff check entry, script exits 0" {
  DROP=sdk/python/pyproject.toml run_check python
  assert_status 0
  assert_only_entry "ruff check" "python requested but no pyproject.toml under src/"
}

@test "java without .java files → failed javac entry, script exits 0" {
  DROP=sdk/java/Foo.java run_check java
  assert_status 0
  assert_only_entry javac "java requested but no .java files under src/"
}

@test "scratch dir goes in WORK_DIR when TMPDIR is not writable" {
  local ro=$BATS_TEST_TMPDIR/ro
  mkdir "$ro" && chmod 555 "$ro"
  (: > "$ro/probe") 2>/dev/null && return 1
  TMPDIR=$ro run_check typescript
  assert_status 0
  [ -f "$WORK_DIR/report.json" ]
  set -- "$WORK_DIR"/.check.*
  [ ! -e "$1" ]
}

@test "ruff cache stays out of out.tar" {
  run_check python
  assert_status 0
  [ "$(tar -tf "$WORK_DIR/out.tar" | grep -c ruff_cache)" -eq 0 ]
}
