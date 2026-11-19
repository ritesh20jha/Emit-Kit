#!/bin/sh
# GEN-12 toolchain image test. Needs a built image: IMAGE (default emitkit/toolchain:test).
# 1. Tools resolve as user 10001 with no network and a read-only rootfs.
# 2. The baseline ruff config is active: UP-style code passes, correctness errors fail.
# 3. The real check.sh contract passes on a TypeScript, Python and Java fixture.
set -eu

IMAGE=${IMAGE:-emitkit/toolchain:test}
WORK=$(mktemp -d) || exit 1
trap 'docker run --rm --user 0 -v "$WORK:/w" --entrypoint sh "$IMAGE" -c "rm -rf /w/* /w/.[!.]*" >/dev/null 2>&1; rm -rf "$WORK"' EXIT

run() {
  docker run --rm --network none --read-only --entrypoint sh "$IMAGE" -c "$1"
}

echo "== tools"
run 'tsc -v && ruff --version && javac -version && prettier --version && google-java-format --version >/dev/null 2>&1 && python -c "import httpx, pydantic" && node -e "require(\"commander\"); require(\"@modelcontextprotocol/sdk/server/mcp.js\")" && id -u' | tail -1 | grep -qx 10001

echo "== no package managers in the final image"
if run 'command -v npm || command -v pip || command -v apt-get || command -v dpkg' >/dev/null 2>&1; then
  echo "package manager still present" >&2
  exit 1
fi

echo "== baseline ruff config is active"
mkdir -p "$WORK/lint"
printf 'from typing import Optional\n\nx: Optional[int] = None\n' > "$WORK/lint/ok.py"
printf 'import os\n\ny = undefined_name\n' > "$WORK/lint/bad.py"
chmod 755 "$WORK/lint"
docker run --rm --network none --read-only --user 10001 -v "$WORK/lint:/w" --entrypoint sh "$IMAGE" \
  -c 'cd /w && ruff check --no-cache ok.py && ! ruff check --no-cache bad.py' || {
  echo "FAIL: baseline ruff config not active (ok.py must pass; bad.py must fail)" >&2
  exit 1
}

echo "== check.sh contract with fixtures"
mkdir -p "$WORK/stage/src/ts" "$WORK/stage/src/py" "$WORK/stage/src/java"
cat > "$WORK/stage/src/ts/package.json" <<'EOF'
{"type": "module"}
EOF
cat > "$WORK/stage/src/ts/tsconfig.json" <<'EOF'
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM"],
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true
  },
  "include": ["."]
}
EOF
cat > "$WORK/stage/src/ts/index.ts" <<'EOF'
import { Command } from "commander";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";

const program = new Command();
program.name("demo");
export const server: typeof Server = Server;
export const name: string = program.name() + process.argv.length;
EOF
printf '[project]\nname = "demo"\nversion = "0.1.0"\n' > "$WORK/stage/src/py/pyproject.toml"
cat > "$WORK/stage/src/py/main.py" <<'EOF'
import httpx
from pydantic import BaseModel


class Item(BaseModel):
    name: str


print(httpx.__version__, Item(name="x"))
EOF
cat > "$WORK/stage/src/java/Foo.java" <<'EOF'
package demo;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Foo {
    static final ObjectMapper MAPPER = new ObjectMapper();
}
EOF
printf 'typescript\npython\njava\n' > "$WORK/stage/languages.txt"
tar -cf "$WORK/in.tar" -C "$WORK/stage" src languages.txt
rm -rf "$WORK/stage"
chmod 777 "$WORK"

docker run --rm --network none --read-only --user 10001 -v "$WORK:/work" "$IMAGE"

jq -e '(.results | length == 6) and all(.results[]; .exitCode == 0)' "$WORK/report.json" >/dev/null || {
  jq . "$WORK/report.json" >&2
  echo "FAIL: not every tool exited 0" >&2
  exit 1
}
jq -r '.results[] | "\(.tool) \(.dir) exit=\(.exitCode)"' "$WORK/report.json"
echo "PASS"
