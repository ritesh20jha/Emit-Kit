// API latency under load: the four non-VCS endpoints (spec section 11, p95 < 300 ms).
// Required env: BASE_URL, SESSION (GitHub login session cookie), PROJECT_ID. See perf/README.md.
import http from "k6/http";
import { check, group, sleep } from "k6";

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

export const options = {
  stages: [
    { duration: "30s", target: 50 },
    { duration: "2m", target: 50 },
    { duration: "15s", target: 0 },
  ],
  thresholds: {
    // k6 names a group "::non-vcs" in the tag, so the selector needs three colons.
    "http_req_duration{group:::non-vcs}": ["p(95)<300"],
    http_req_failed: ["rate<0.01"],
  },
};

export default function () {
  http.cookieJar().set(BASE_URL, "SESSION", SESSION);

  group("non-vcs", () => {
    const project = http.get(`${BASE_URL}/api/v1/projects/${PROJECT_ID}`);
    check(project, { "project is 200": (r) => r.status === 200 });

    const runs = http.get(`${BASE_URL}/api/v1/projects/${PROJECT_ID}/runs?limit=1`);
    check(runs, { "runs list is 200": (r) => r.status === 200 });
    const runId = runs.status === 200 ? runs.json("items.0.id") : null;
    check(runId, { "project has a run to read": (id) => !!id });

    if (runId) {
      const run = http.get(`${BASE_URL}/api/v1/runs/${runId}`);
      check(run, { "run detail is 200": (r) => r.status === 200 });
    }

    const config = http.get(`${BASE_URL}/api/v1/projects/${PROJECT_ID}/config`);
    check(config, { "config is 200": (r) => r.status === 200 });
  });

  sleep(1);
}
