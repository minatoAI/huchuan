import test from "node:test";
import assert from "node:assert/strict";

import * as workerModule from "../src/index.js";
import { createTestEnv } from "../test-kit/memory.js";
import { makeRequest } from "../test-kit/memory.js";

test("GET /health 回 ok 与三个 DO 绑定", async () => {
  const env = createTestEnv(workerModule);
  const response = await workerModule.route(makeRequest("/health"), env, undefined);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.ok, true);
  assert.equal(body.service, "huchuan-worker");
  assert.deepEqual(body.bindings, { SWARM: true, REGISTRY: true, ROOM: true });
});

test("未知路径回 404，方法不对回 405", async () => {
  const env = createTestEnv(workerModule);
  const missing = await workerModule.route(makeRequest("/nope"), env, undefined);
  assert.equal(missing.status, 404);
  const wrongMethod = await workerModule.route(makeRequest("/api/share", { method: "GET" }), env, undefined);
  assert.equal(wrongMethod.status, 405);
});
