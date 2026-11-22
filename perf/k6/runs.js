// Run duration under concurrency: 8 VUs each start one dry run of the 200-operation spec and poll
// until it reaches a terminal status (spec section 11, p95 < 3 min). Required env: BASE_URL,
// SESSION, PROJECT_ID (a project whose repo holds big-200-ops.yaml). See perf/README.md.
import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is required. See perf/README.md.`);
  }
  return value;
}

const BASE_URL = required("BASE_URL").replace(/\/$/, "");
const SESSION = required("SESSION");
const PROJECT_ID = required("PROJECT_ID");

// A run that has not finished by then is cancelled and counts as a failure, so it cannot hang k6.
const RUN_TIMEOUT_MS = 10 * 60 * 1000;
const POLL_INTERVAL_S = 5;
const TERMINAL = ["SUCCESS", "FAILED", "CANCELLED"];

// Only successful runs are timed: a fast failure must not pull the p95 down.
const runDuration = new Trend("run_duration", true);
const runFailures = new Rate("run_failures");

export const options = {
  scenarios: {
    dry_runs: {
      executor: "per-vu-iterations",
      vus: 8,
      iterations: 1,
      maxDuration: "15m",
    },
  },
  thresholds: {
    run_duration: ["p(95)<180000"],
    // With no successful run, run_duration has no samples; this rate catches that case.
    run_failures: ["rate==0"],
    http_req_failed: ["rate<0.01"],
  },
};

// One request before any load: a bad SESSION fails here once, not as 8 identical run failures.
export function setup() {
  http.cookieJar().set(BASE_URL, "SESSION", SESSION);
  const res = http.get(`${BASE_URL}/api/v1/projects/${PROJECT_ID}`);
  if (res.status !== 200) {
    const hint = res.status === 401 || res.status === 403 ? "check SESSION" : "check PROJECT_ID";
    throw new Error(`preflight GET project failed: HTTP ${res.status} (${hint})`);
  }
}

export default function () {
  http.cookieJar().set(BASE_URL, "SESSION", SESSION);

  // Every response carries the XSRF-TOKEN cookie; the POST must echo it in X-XSRF-TOKEN.
  http.get(`${BASE_URL}/api/v1/projects/${PROJECT_ID}`);
  const xsrf = http.cookieJar().cookiesForURL(BASE_URL)["XSRF-TOKEN"];
  if (!xsrf) {
    console.error("no XSRF-TOKEN cookie was issued; cannot start a run");
    runFailures.add(true);
    return;
  }
  const csrfHeaders = { "Content-Type": "application/json", "X-XSRF-TOKEN": xsrf[0] };

  const started = Date.now();
  const created = http.post(
    `${BASE_URL}/api/v1/projects/${PROJECT_ID}/runs`,
    JSON.stringify({ dryRun: true }),
    { headers: csrfHeaders },
  );
  check(created, { "run started (201)": (r) => r.status === 201 });
  const runId = created.status === 201 ? created.json("id") : null;
  if (!runId) {
    console.error(`run was not started: HTTP ${created.status}`);
    runFailures.add(true);
    return;
  }

  let status = null;
  let pollFailed = false;
  while (Date.now() - started < RUN_TIMEOUT_MS) {
    sleep(POLL_INTERVAL_S);
    const res = http.get(`${BASE_URL}/api/v1/runs/${runId}`);
    if (res.status !== 200) {
      console.error(`polling run ${runId} failed: HTTP ${res.status}`);
      pollFailed = true;
      break;
    }
    status = res.json("status");
    // AWAITING_APPROVAL only resolves with a reviewer, so do not wait for it.
    if (TERMINAL.includes(status) || status === "AWAITING_APPROVAL") {
      break;
    }
  }

  const elapsed = Date.now() - started;
  const succeeded = status === "SUCCESS";
  if (succeeded) {
    runDuration.add(elapsed);
  } else if (status === "AWAITING_APPROVAL") {
    console.error(
      `run ${runId} is AWAITING_APPROVAL: set the project's breakingGate to ALLOW (perf/README.md)`,
    );
  } else if (!pollFailed && !TERMINAL.includes(status)) {
    console.error(`run ${runId} did not finish within ${RUN_TIMEOUT_MS} ms`);
  } else if (!pollFailed) {
    console.error(`run ${runId} ended as ${status} after ${elapsed} ms`);
  }

  // Best effort: a run left parked or stuck would hold a slot on the project. The result is logged only.
  if (!succeeded && !pollFailed && !TERMINAL.includes(status)) {
    const cancel = http.post(`${BASE_URL}/api/v1/runs/${runId}/cancel`, null, {
      headers: csrfHeaders,
    });
    console.error(`cancel run ${runId}: HTTP ${cancel.status}`);
  }
  runFailures.add(!succeeded);
}
