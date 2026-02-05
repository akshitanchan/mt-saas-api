import http from "k6/http";
import { check, sleep, fail } from "k6";
import { Trend, Rate } from "k6/metrics";

const authReqLinkP95 = new Trend("p95_auth_request_link", true);
const tasksCreateP95 = new Trend("p95_tasks_create", true);
const tasksListP95 = new Trend("p95_tasks_list", true);
const webhookStripeP95 = new Trend("p95_webhook_stripe", true);
const failRate = new Rate("fail_rate");
const webhookSuccessRate = new Rate("webhook_success_rate");

export const options = {
  vus: __ENV.VUS ? parseInt(__ENV.VUS, 10) : 1,
  duration: __ENV.DURATION || "20s",

  // don’t let “exit 0” lie to you
  thresholds: {
    // k6 built-ins
    checks: ["rate>0.99"],
    http_req_failed: ["rate<0.01"],

    // your custom “fail_rate”
    fail_rate: ["rate<0.01"],

    // per-endpoint latency (tune these if you want stricter/slacker)
    p95_tasks_create: ["p(95)<200"],
    p95_tasks_list: ["p(95)<200"],
    webhook_success_rate: ["rate>0.99"],
  },
};

const BASE = __ENV.BASE_URL || "http://api:8000";

// add request tags so built-in metrics can be sliced by name
function postJson(path, body, headers = {}, tags = {}) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), {
    headers: { "content-type": "application/json", ...headers },
    tags,
  });
}

function get(path, headers = {}, tags = {}) {
  return http.get(`${BASE}${path}`, { headers, tags });
}

// tiny retry helper: retries on 429 + 5xx with backoff
function withRetry(fn, { tries = 6, baseSleep = 0.25 } = {}) {
  let last;
  for (let i = 0; i < tries; i++) {
    last = fn();
    const code = last && last.status;

    // success
    if (code && code < 400) return last;

    // retryable
    if (code === 429 || (code >= 500 && code <= 599)) {
      sleep(baseSleep * Math.pow(2, i)); // 0.25, 0.5, 1, 2, 4...
      continue;
    }

    // non-retryable
    return last;
  }
  return last;
}

function login(email) {
  let r = withRetry(
    () =>
      postJson(
        "/auth/request-link",
        { email },
        {},
        { name: "auth_request_link" }
      ),
    { tries: 8, baseSleep: 0.2 }
  );

  authReqLinkP95.add(r.timings.duration);
  failRate.add(r.status !== 200);
  if (!check(r, { "request-link 200": (x) => x.status === 200 })) {
    fail(`setup login failed at request-link: status=${r.status} body=${r.body}`);
  }

  const token = r.json("token");
  if (!token) fail(`setup login missing token. status=${r.status} body=${r.body}`);

  r = withRetry(
    () => postJson("/auth/redeem", { token }, {}, { name: "auth_redeem" }),
    { tries: 8, baseSleep: 0.2 }
  );

  failRate.add(r.status !== 200);
  if (!check(r, { "redeem 200": (x) => x.status === 200 })) {
    fail(`setup login failed at redeem: status=${r.status} body=${r.body}`);
  }

  const jwt = r.json("access_token");
  if (!jwt) fail(`setup login missing jwt. status=${r.status} body=${r.body}`);
  return jwt;
}

export function setup() {
  // wait for ready (hard fail if never comes up)
  let readyOk = false;
  for (let i = 0; i < 80; i++) {
    const r = get("/ready", {}, { name: "ready" });
    if (r.status === 200) {
      readyOk = true;
      break;
    }
    sleep(0.25);
  }
  if (!readyOk) fail("api never became ready");

  // IMPORTANT: only do auth once, otherwise auth rate limiting will wreck load runs
  const email = `k6_setup_${Date.now()}@example.com`;
  const jwt = login(email);

  let r = withRetry(
    () =>
      postJson(
        "/orgs",
        { name: `k6 org ${Date.now()}` },
        { authorization: `bearer ${jwt}` },
        { name: "org_create" }
      ),
    { tries: 6, baseSleep: 0.25 }
  );
  failRate.add(r.status !== 200);
  if (!check(r, { "org create 200": (x) => x.status === 200 })) {
    fail(`setup org create failed: status=${r.status} body=${r.body}`);
  }
  const orgId = r.json("id");
  if (!orgId) fail(`setup missing orgId. status=${r.status} body=${r.body}`);

  r = withRetry(
    () =>
      postJson(
        `/orgs/${orgId}/projects`,
        { name: "k6 proj" },
        { authorization: `bearer ${jwt}` },
        { name: "project_create" }
      ),
    { tries: 6, baseSleep: 0.25 }
  );
  failRate.add(r.status !== 200);
  if (!check(r, { "project create 200": (x) => x.status === 200 })) {
    fail(`setup project create failed: status=${r.status} body=${r.body}`);
  }
  const projectId = r.json("id");
  if (!projectId) fail(`setup missing projectId. status=${r.status} body=${r.body}`);

  return { jwt, orgId, projectId };
}

export default function (data) {
  const { jwt, orgId, projectId } = data;

  // create task
  let r = postJson(
    `/orgs/${orgId}/projects/${projectId}/tasks`,
    { title: `k6 task ${__VU}_${__ITER}`, description: "load" },
    { authorization: `bearer ${jwt}` },
    { name: "tasks_create" }
  );
  tasksCreateP95.add(r.timings.duration);
  failRate.add(r.status !== 200);
  check(r, { "task create 200": (x) => x.status === 200 });

  // list tasks
  r = get(
    `/orgs/${orgId}/projects/${projectId}/tasks`,
    { authorization: `bearer ${jwt}` },
    { name: "tasks_list" }
  );
  tasksListP95.add(r.timings.duration);
  failRate.add(r.status !== 200);
  check(r, { "task list 200": (x) => x.status === 200 });

  // stripe webhook (every 5th iteration)
  if (__ITER % 5 === 0) {
    const eventId = `evt_k6_${__VU}_${__ITER}_${Date.now()}`;
    const customerId = `cus_k6_${__VU}`;
    r = withRetry(
      () =>
        postJson(
          "/webhooks/stripe",
          {
            id: eventId,
            type: "invoice.paid",
            data: {
              object: {
                id: `in_k6_${__VU}_${__ITER}`,
                customer: customerId,
                subscription: `sub_k6_${__VU}`,
                metadata: { org_id: orgId },
              },
            },
          },
          {},
          { name: "webhook_stripe" }
        ),
      { tries: 6, baseSleep: 0.25 }
    );
    webhookStripeP95.add(r.timings.duration);
    webhookSuccessRate.add(r.status === 200);
    check(r, { "webhook 200": (x) => x.status === 200 });
  }

  sleep(0.2);
}

export function handleSummary(data) {
  const summaryPath = __ENV.K6_SUMMARY_PATH || "/results/summary.json";

  const out = {
    meta: {
      run_id: __ENV.RUN_ID || "local",
      git_sha: __ENV.GIT_SHA || "nogit",
      base_url: __ENV.BASE_URL || "http://api:8000",
      vus: Number(__ENV.VUS || 1),
      duration: __ENV.DURATION || "20s",
      created_at: new Date().toISOString(),
    },
    k6: data,
  };

  return {
    [summaryPath]: JSON.stringify(out, null, 2),
  };
}
