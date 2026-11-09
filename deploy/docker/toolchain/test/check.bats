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
